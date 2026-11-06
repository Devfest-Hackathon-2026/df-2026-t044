package com.example.yobareta.model

data class UserWatchProfile(
    val kanjiName: String = "東海林 太郎",
    val selectedFullReading: String = "ショウジ タロウ",
    val selectedSurnameReading: String = "ショウジ",
    val alternateFullReadings: List<String> = listOf("ショウジタロウ", "トウカイリンタロウ", "Shouji Tarou", "Tarou Shouji"),
    val alternateSurnameReadings: List<String> = listOf("ショウジ", "トウカイリン", "Shouji"),
    val ticketNumber: String = "",
    val venueMode: String = "auto",     // "auto" (all venues) | "hospital" | "restaurant" | "cafe"
    val languageMode: String = "auto",  // "auto" (JA+EN) | "ja-JP" | "en-US"
    val cloudRunBaseUrl: String = ""
) {
    private fun normalizeReadingEntry(entry: String): String {
        val trimmed = entry.replace("　", " ").trim()
        val hasLatin = trimmed.any { it in 'A'..'Z' || it in 'a'..'z' }
        return if (hasLatin) {
            // Keep Latin names as typed (hyphenated names like "Smith-Jones" stay intact)
            trimmed.replace(Regex("\\s+"), " ")
        } else {
            trimmed.replace(" ", "")
        }
    }

    fun allFullNameReadings(): List<String> {
        val list = mutableListOf<String>()
        if (kanjiName.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            list.add(normalizeReadingEntry(kanjiName))
        }
        list.add(normalizeReadingEntry(selectedFullReading))
        for (alt in alternateFullReadings) {
            list.add(normalizeReadingEntry(alt))
        }
        return list.filter { it.isNotEmpty() }.distinct()
    }

    fun allSurnameReadings(): List<String> {
        val list = mutableListOf<String>()
        if (kanjiName.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            val words = kanjiName.replace("　", " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isNotEmpty()) {
                list.add(normalizeReadingEntry(words.last()))
            }
        }
        list.add(normalizeReadingEntry(selectedSurnameReading))
        for (alt in alternateSurnameReadings) {
            list.add(normalizeReadingEntry(alt))
        }
        return list.filter { it.isNotEmpty() }.distinct()
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
