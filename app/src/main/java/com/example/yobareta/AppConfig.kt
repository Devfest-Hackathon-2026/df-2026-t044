package com.example.yobareta

import android.content.Context
import java.util.Properties

object AppConfig {
    private const val PLACEHOLDER_URL = "https://your-cloud-run-service.asia-northeast1.run.app"

    @Volatile
    var DEFAULT_CLOUD_RUN_BASE_URL: String = PLACEHOLDER_URL
        private set

    @Volatile
    var API_SECRET: String = ""
        private set

    /**
     * Loads optional git-ignored `app/src/main/assets/yobareta.env` if present on device.
     */
    fun initFromContext(context: Context) {
        try {
            val assets = context.assets.list("") ?: return
            if ("yobareta.env" in assets) {
                context.assets.open("yobareta.env").use { input ->
                    val props = Properties().apply { load(input) }
                    val url = props.getProperty("CLOUD_RUN_BASE_URL")?.trim().orEmpty()
                    if (url.isNotEmpty() && !url.contains("your-cloud-run-service")) {
                        DEFAULT_CLOUD_RUN_BASE_URL = url.trimEnd('/')
                    }
                    val secret = props.getProperty("YOBARETA_API_SECRET")?.trim().orEmpty()
                    if (secret.isNotEmpty() && !secret.startsWith("replace-with")) {
                        API_SECRET = secret
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
