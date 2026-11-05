package com.example.yobareta.net

import android.util.Base64
import android.util.Log
import com.example.yobareta.BuildConfig
import com.example.yobareta.auth.AuthManager
import com.example.yobareta.model.RecognizeAlertResult
import com.example.yobareta.model.UserWatchProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YobaretaCloudClient(
    private val authManager: AuthManager = AuthManager()
) {
    companion object {
        private const val TAG = "YobaretaCloudClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun resolveBaseUrl(overrideUrl: String): String {
        val raw = overrideUrl.trim().ifEmpty { BuildConfig.CLOUD_RUN_BASE_URL }
        return raw.trimEnd('/')
    }

    /**
     * Pings /warm when the user taps Start to eliminate Cloud Run cold starts.
     */
    suspend fun warmUp(baseUrlOverride: String = ""): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(baseUrlOverride)
        try {
            val idToken = authManager.getIdToken()
            val appCheckToken = authManager.getAppCheckToken()

            val request = Request.Builder()
                .url("$baseUrl/warm")
                .get()
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("X-Firebase-AppCheck", appCheckToken)
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "/warm returned HTTP ${resp.code}")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "/warm failed: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Uploads a 2–8 s 16 kHz mono LINEAR16 audio segment to POST /recognize.
     */
    suspend fun recognizeSegment(
        pcm16LeBytes: ByteArray,
        profile: UserWatchProfile,
        previouslyCalled: Boolean
    ): RecognizeAlertResult = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(profile.cloudRunBaseUrl)
        val idToken = authManager.getIdToken()
        val appCheckToken = authManager.getAppCheckToken()

        val audioB64 = Base64.encodeToString(pcm16LeBytes, Base64.NO_WRAP)
        val bodyJson = JSONObject().apply {
            put("audio_b64", audioB64)
            put("full_name_readings", JSONArray(profile.allFullNameReadings()))
            put("surname_readings", JSONArray(profile.allSurnameReadings()))
            put("kanji_name", profile.kanjiName.trim())
            put("ticket_number", profile.ticketNumber.trim())
            put("venue_mode", profile.venueMode)
            put("language_mode", profile.languageMode)
            put("previously_called", previouslyCalled)
        }

        val request = Request.Builder()
            .url("$baseUrl/recognize")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("X-Firebase-AppCheck", appCheckToken)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val rawBody = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                throw IllegalStateException("Cloud Run /recognize HTTP ${resp.code}: $rawBody")
            }
            val json = JSONObject(rawBody)
            RecognizeAlertResult(
                tier = json.optString("tier", "none"),
                score = json.optDouble("score", 0.0).toFloat(),
                matchedTrigger = json.optString("matched_trigger", ""),
                transcriptMasked = json.optString("transcript_masked", ""),
                destination = json.optString("destination", ""),
                instructionPlain = json.optString("instruction_plain", ""),
                latencyMs = json.optInt("latency_ms", 0)
            )
        }
    }
}
