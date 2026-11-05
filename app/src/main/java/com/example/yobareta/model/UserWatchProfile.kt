package com.example.yobareta.model

data class UserWatchProfile(
    val kanjiName: String = "東海林 太郎",
    val selectedFullReading: String = "ショウジ タロウ",
    val selectedSurnameReading: String = "ショウジ",
    val alternateFullReadings: List<String> = listOf("ショウジタロウ", "トウカイリンタロウ"),
    val alternateSurnameReadings: List<String> = listOf("ショウジ", "トウカイリン"),
    val ticketNumber: String = "",
    val venueMode: String = "hospital", // "hospital" | "restaurant" | "cafe"
    val cloudRunBaseUrl: String = ""
) {
    fun allFullNameReadings(): List<String> {
        val primary = selectedFullReading.replace(" ", "").replace("　", "").trim()
        return (listOf(primary) + alternateFullReadings.map { it.replace(" ", "").trim() })
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun allSurnameReadings(): List<String> {
        val primary = selectedSurnameReading.replace(" ", "").replace("　", "").trim()
        return (listOf(primary) + alternateSurnameReadings.map { it.replace(" ", "").trim() })
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

data class RecognizeAlertResult(
    val tier: String, // "none" | "possible" | "called" | "recall"
    val score: Float,
    val matchedTrigger: String,
    val transcriptMasked: String,
    val destination: String,
    val instructionPlain: String,
    val latencyMs: Int,
    val timestampEpochMs: Long = System.currentTimeMillis()
)
