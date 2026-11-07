package com.example.yobareta.auth

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

class AuthManager {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    companion object {
        private const val TAG = "AuthManager"
        @Volatile
        private var appCheckInitialized = false

        fun initAppCheck(context: Context) {
            if (appCheckInitialized) return
            synchronized(this) {
                if (appCheckInitialized) return
                FirebaseApp.initializeApp(context)
                val firebaseAppCheck = FirebaseAppCheck.getInstance()
                val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebuggable) {
                    Log.d(TAG, "Installing DebugAppCheckProviderFactory for debug build")
                    firebaseAppCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                } else {
                    firebaseAppCheck.installAppCheckProviderFactory(
                        PlayIntegrityAppCheckProviderFactory.getInstance()
                    )
                }
                appCheckInitialized = true
            }
        }
    }

    suspend fun ensureAuthenticated(): String {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            return currentUser.uid
        }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: throw IllegalStateException("Anonymous authentication failed.")
    }

    suspend fun getIdToken(forceRefresh: Boolean = false): String {
        return try {
            ensureAuthenticated()
            val user = auth.currentUser ?: throw IllegalStateException("No authenticated Firebase user.")
            val tokenResult = user.getIdToken(forceRefresh).await()
            tokenResult.token ?: "unregistered-id-token"
        } catch (e: Exception) {
            Log.w(TAG, "Firebase ID token fetch skipped/failed (using API secret header if configured): ${e.message}")
            "anonymous-auth-disabled"
        }
    }

    suspend fun getAppCheckToken(forceRefresh: Boolean = false): String {
        return try {
            val tokenResult = FirebaseAppCheck.getInstance().getAppCheckToken(forceRefresh).await()
            tokenResult.token
        } catch (e: Exception) {
            Log.w(TAG, "AppCheck token fetch failed (register debug token in Firebase Console if testing): ${e.message}")
            "unregistered-debug-appcheck-token"
        }
    }
}
