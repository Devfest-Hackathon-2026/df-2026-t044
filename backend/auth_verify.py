"""Firebase Auth ID token and Firebase App Check token verification.

Uses Firebase Admin SDK with Application Default Credentials (no service account keys committed).
"""

from __future__ import annotations

import hashlib
import logging
import os
from typing import Optional

from fastapi import Header, HTTPException, status
import firebase_admin
from firebase_admin import app_check, auth

logger = logging.getLogger("yobareta.auth")

_FIREBASE_INITIALIZED = False


def ensure_firebase_initialized() -> None:
    global _FIREBASE_INITIALIZED
    if _FIREBASE_INITIALIZED:
        return
    if not firebase_admin._apps:
        project_id = (
            os.environ.get("GOOGLE_CLOUD_PROJECT")
            or os.environ.get("GCP_PROJECT_ID")
            or "yobareta"
        )
        firebase_admin.initialize_app(options={"projectId": project_id})
    _FIREBASE_INITIALIZED = True


def _env_bool(name: str, default: bool = True) -> bool:
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


async def verify_request_tokens(
    authorization: Optional[str] = Header(default=None),
    x_firebase_appcheck: Optional[str] = Header(default=None, alias="X-Firebase-AppCheck"),
) -> str:
    """Verifies Firebase Auth ID token and Firebase App Check token.

    Returns an anonymized SHA-256 hash prefix of the caller's UID for short-lived
    re-call detection in RAM (never logging or storing raw PII).
    """
    ensure_firebase_initialized()

    require_auth = _env_bool("REQUIRE_AUTH", default=True)
    require_app_check = _env_bool("REQUIRE_APP_CHECK", default=True)

    uid = "anonymous-dev"
    if require_auth:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Missing or invalid Authorization Bearer token",
            )
        id_token = authorization.split(" ", 1)[1].strip()
        try:
            decoded = auth.verify_id_token(id_token)
            uid = decoded.get("uid", "unknown")
        except Exception as exc:
            logger.warning("Firebase Auth token verification failed: %s", type(exc).__name__)
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid Firebase Auth ID token",
            ) from exc

    if require_app_check:
        if not x_firebase_appcheck or not x_firebase_appcheck.strip():
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Missing X-Firebase-AppCheck header",
            )
        try:
            app_check.verify_token(x_firebase_appcheck.strip())
        except Exception as exc:
            logger.warning("Firebase App Check verification failed: %s", type(exc).__name__)
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid Firebase App Check token",
            ) from exc

    # Return an irreversible short hash of UID for ephemeral re-call state in memory
    return hashlib.sha256(uid.encode("utf-8")).hexdigest()[:16]
