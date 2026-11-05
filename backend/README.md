# Yobareta Cloud Run Backend (`asia-northeast1`)

Stateless Python FastAPI service for **Yobareta**:
- **Endpoints**:
  - `GET /warm` / `POST /warm`: Pre-warms `SudachiPy` dictionary, Speech-to-Text V2 (`chirp_3`) client, Agent Platform Gemini Flash client, and Firebase Admin SDK when the user taps **Start**.
  - `POST /recognize`: Verifies Firebase Auth ID token + Firebase App Check token, transcribes 16 kHz mono 16-bit PCM (`LINEAR16`) via Speech-to-Text V2 (`chirp_3` at `us-speech.googleapis.com`), runs the deterministic `SudachiPy` mora edit-distance matcher, and verifies candidate matches with Gemini Flash on Agent Platform (`2.5s` timeout with automatic fallback).
- **Privacy Guarantee**:
  - Zero audio or transcripts are ever logged or persisted (waiting room announcements contain other patients' names).
  - Logs only structured latency and score metrics.

---

## One-Time Google Cloud Setup (Project: `yobareta`)

Run these commands in **Google Cloud Shell** (or a local terminal with `gcloud` installed):

```bash
export PROJECT_ID="yobareta"
export REGION="asia-northeast1"

gcloud config set project "$PROJECT_ID"

# 1. Enable required Google Cloud & Firebase APIs
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  speech.googleapis.com \
  aiplatform.googleapis.com \
  texttospeech.googleapis.com \
  firebase.googleapis.com \
  firebaseappcheck.googleapis.com

# 2. Create Artifact Registry repository in asia-northeast1 (if using cloudbuild.yaml)
gcloud artifacts repositories create yobareta-repo \
  --repository-format=docker \
  --location="$REGION" \
  --description="Yobareta Cloud Run images" || true

# 3. Create a dedicated least-privilege Service Account for Cloud Run (no API keys!)
gcloud iam service-accounts create yobareta-runner \
  --display-name="Yobareta Cloud Run Service Account" || true

export SA_EMAIL="yobareta-runner@${PROJECT_ID}.iam.gserviceaccount.com"

# Grant Speech-to-Text, Agent Platform (Vertex AI), and Firebase token verification roles
for ROLE in \
  roles/speech.client \
  roles/aiplatform.user \
  roles/firebaseauth.admin \
  roles/firebaseappcheck.tokenVerifier
do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="$ROLE"
done
```

---

## Deploy to Cloud Run (`asia-northeast1`)

From the root of the repository:

```bash
gcloud run deploy yobareta-api \
  --source ./backend \
  --region asia-northeast1 \
  --service-account "yobareta-runner@yobareta.iam.gserviceaccount.com" \
  --allow-unauthenticated \
  --set-env-vars "GOOGLE_CLOUD_PROJECT=yobareta,STT_LOCATION=us,STT_MODEL=chirp_3,GEMINI_LOCATION=asia-northeast1,GEMINI_MODEL=gemini-2.5-flash,REQUIRE_AUTH=true,REQUIRE_APP_CHECK=true" \
  --memory 1Gi \
  --cpu 1 \
  --concurrency 20 \
  --timeout 15s
```
*(Note: `--allow-unauthenticated` at the Cloud Run IAM layer allows mobile devices to reach the HTTPS endpoint directly, while `verify_request_tokens` inside FastAPI strictly enforces Firebase Auth ID tokens + Firebase App Check tokens on `/warm` and `/recognize`.)*
