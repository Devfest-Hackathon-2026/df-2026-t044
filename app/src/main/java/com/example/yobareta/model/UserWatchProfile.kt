package com.example.yobareta.model

data class UserWatchProfile(
    val kanjiName: String = "",
    val selectedFullReading: String = "",
    val selectedSurnameReading: String = "",
    val selectedGivenReading: String = "",
    val alternateFullReadings: List<String> = emptyList(),
    val alternateSurnameReadings: List<String> = emptyList(),
    val alternateGivenReadings: List<String> = emptyList(),
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

    fun allGivenNameReadings(): List<String> {
        val list = mutableListOf<String>()
        val hasLatin = kanjiName.any { it in 'A'..'Z' || it in 'a'..'z' }
        val words = kanjiName.replace("　", " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > 1) {
            val given = if (hasLatin) words.first() else words.last()
            list.add(normalizeReadingEntry(given))
        }
        if (selectedGivenReading.isNotBlank()) {
            list.add(normalizeReadingEntry(selectedGivenReading))
        }
        for (alt in alternateGivenReadings) {
            list.add(normalizeReadingEntry(alt))
        }
        val surNormalized = allSurnameReadings()
        for (full in allFullNameReadings()) {
            for (sur in surNormalized) {
                if (full.startsWith(sur) && full.length > sur.length) {
                    val rem = full.substring(sur.length).trim()
                    if (rem.length >= 2) list.add(normalizeReadingEntry(rem))
                } else if (full.endsWith(sur) && full.length > sur.length) {
                    val rem = full.substring(0, full.length - sur.length).trim()
                    if (rem.length >= 2) list.add(normalizeReadingEntry(rem))
                }
            }
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
