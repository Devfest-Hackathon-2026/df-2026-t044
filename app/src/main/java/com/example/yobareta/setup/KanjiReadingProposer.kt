package com.example.yobareta.setup

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.vertexai.type.content
import com.google.firebase.vertexai.type.generationConfig
import com.google.firebase.vertexai.vertexAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class ReadingOption(
    val fullReading: String,      // e.g. "ショウジ タロウ" or "Alex Smith"
    val surnameReading: String,   // e.g. "ショウジ" or "Smith"
    val note: String = ""         // e.g. "一般的な読み" or "English + Katakana"
)

object KanjiReadingProposer {
    private const val TAG = "KanjiReadingProposer"

    // Curated instant lookup for famous multi-reading Japanese surnames
    private val MULTI_READING_SURNAMES: Map<String, List<Pair<String, String>>> = mapOf(
        "東海林" to listOf("ショウジ" to "一般的な読み", "トウカイリン" to "別読み"),
        "小鳥遊" to listOf("タカナシ" to "難読姓"),
        "四月一日" to listOf("ワタヌキ" to "難読姓", "ツボミ" to "別読み"),
        "月見里" to listOf("ヤマナシ" to "難読姓"),
        "神谷" to listOf("カミヤ" to "一般的", "カミタニ" to "関西など"),
        "山崎" to listOf("ヤマザキ" to "東日本中心", "ヤマサキ" to "西日本中心"),
        "中島" to listOf("ナカジマ" to "一般的", "ナカシマ" to "九州・西日本"),
        "河野" to listOf("コウノ" to "一般的", "カワノ" to "九州など"),
        "大野" to listOf("オオノ" to "長音: オーノと同等"),
        "佐藤" to listOf("サトウ" to "長音: サトーと同等"),
        "鈴木" to listOf("スズキ" to "標準"),
        "高橋" to listOf("タカハシ" to "標準"),
        "田中" to listOf("タナカ" to "標準"),
        "渡辺" to listOf("ワタナベ" to "標準"),
        "伊藤" to listOf("イトウ" to "長音: イトーと同等"),
        "山本" to listOf("ヤマモト" to "標準"),
        "中村" to listOf("ナカムラ" to "標準"),
        "小林" to listOf("コバヤシ" to "標準"),
        "加藤" to listOf("カトウ" to "長音: カトーと同等")
    )

    private val COMMON_GIVEN_NAMES: Map<String, List<String>> = mapOf(
        "太郎" to listOf("タロウ"),
        "一郎" to listOf("イチロウ"),
        "花子" to listOf("ハナコ"),
        "健太" to listOf("ケンタ"),
        "大輔" to listOf("ダイスケ"),
        "翔太" to listOf("ショウタ"),
        "美咲" to listOf("ミサキ"),
        "陽菜" to listOf("ヒナ", "ハルナ"),
        "誠" to listOf("マコト"),
        "直樹" to listOf("ナオキ")
    )

    // Common English/international names -> Katakana transliterations for bilingual protection
    private val ENGLISH_TO_KATAKANA: Map<String, String> = mapOf(
        "alex" to "アレックス",
        "smith" to "スミス",
        "john" to "ジョン",
        "johnson" to "ジョンソン",
        "michael" to "マイケル",
        "david" to "デイビッド",
        "sarah" to "サラ",
        "emily" to "エミリー",
        "james" to "ジェームズ",
        "robert" to "ロバート",
        "william" to "ウィリアム",
        "brown" to "ブラウン",
        "davis" to "デイビス",
        "miller" to "ミラー",
        "wilson" to "ウィルソン",
        "taylor" to "テイラー",
        "thomas" to "トーマス",
        "lee" to "リー",
        "chen" to "チェン",
        "wang" to "ワン",
        "kim" to "キム",
        "park" to "パク",
        "shoji" to "ショウジ",
        "taro" to "タロウ",
        "sato" to "サトウ",
        "suzuki" to "スズキ",
        "tanaka" to "タナカ"
    )

    /**
     * Proposes both Katakana and English readings for a Japanese or English personal name
     * using Gemini via Firebase AI Logic, with an instant local fallback dictionary.
     */
    suspend fun proposeReadings(nameInput: String): List<ReadingOption> = withContext(Dispatchers.IO) {
        val cleaned = nameInput.trim()
        if (cleaned.isEmpty()) return@withContext emptyList()

        val localSuggestions = buildLocalFallback(cleaned)

        try {
            withTimeout(4000L) {
                val model = Firebase.vertexAI.generativeModel(
                    modelName = "gemini-2.0-flash",
                    generationConfig = generationConfig {
                        temperature = 0.1f
                        maxOutputTokens = 256
                        responseMimeType = "application/json"
                    }
                )
                val prompt = """
                    Propose both Japanese Katakana readings and English/Romaji readings for the personal name "$cleaned".
                    Return a JSON object with key "readings", which is an array of objects:
                    [
                      {"full_reading": "ショウジ タロウ", "surname_reading": "ショウジ", "note": "Katakana reading"},
                      {"full_reading": "Shoji Taro", "surname_reading": "Shoji", "note": "English / Romaji"}
                    ]
                    If the name has multiple Japanese readings (e.g. 東海林 -> ショウジ and トウカイリン), include all of them.
                    If the input is an English name (e.g. "Alex Smith"), include both the English name ("Alex Smith", surname "Smith") and its Japanese Katakana pronunciation ("アレックス スミス", surname "スミス").
                """.trimIndent()

                val response = model.generateContent(content { text(prompt) })
                val rawJson = response.text ?: "{}"
                val parsed = parseJsonReadings(rawJson)
                if (parsed.isNotEmpty()) {
                    return@withTimeout (parsed + localSuggestions)
                        .distinctBy { it.fullReading.lowercase().replace(" ", "") }
                }
                localSuggestions
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase AI Logic fallback to local dictionary: ${e.message}")
            localSuggestions
        }
    }

    private fun parseJsonReadings(rawJson: String): List<ReadingOption> {
        return try {
            val root = JSONObject(rawJson)
            val arr: JSONArray = root.optJSONArray("readings") ?: return emptyList()
            val out = mutableListOf<ReadingOption>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val full = item.optString("full_reading").trim()
                val surname = item.optString("surname_reading").trim()
                val note = item.optString("note").trim()
                if (full.isNotEmpty() && surname.isNotEmpty()) {
                    out.add(ReadingOption(fullReading = full, surnameReading = surname, note = note))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildLocalFallback(nameInput: String): List<ReadingOption> {
        val normalized = nameInput.replace("　", " ").trim()
        val hasLatin = normalized.any { it in 'A'..'Z' || it in 'a'..'z' }

        if (hasLatin) {
            val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val surnameEn = if (words.size >= 2) words.last() else words.first()
            val options = mutableListOf(
                ReadingOption(
                    fullReading = normalized,
                    surnameReading = surnameEn,
                    note = "English match"
                )
            )
            // Also generate Katakana transliteration if known in dictionary
            val kataWords = words.mapNotNull { ENGLISH_TO_KATAKANA[it.lowercase()] }
            if (kataWords.isNotEmpty()) {
                val kataSurname = ENGLISH_TO_KATAKANA[surnameEn.lowercase()] ?: kataWords.last()
                options.add(
                    ReadingOption(
                        fullReading = kataWords.joinToString(" "),
                        surnameReading = kataSurname,
                        note = "Japanese PA Katakana"
                    )
                )
            }
            return options
        }

        val parts = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val surnameKanji: String
        val givenKanji: String
        if (parts.size >= 2) {
            surnameKanji = parts[0]
            givenKanji = parts.drop(1).joinToString("")
        } else {
            val matchedPrefix = MULTI_READING_SURNAMES.keys.firstOrNull { normalized.startsWith(it) }
            if (matchedPrefix != null) {
                surnameKanji = matchedPrefix
                givenKanji = normalized.removePrefix(matchedPrefix)
            } else {
                surnameKanji = normalized
                givenKanji = ""
            }
        }

        val surnamePairs = MULTI_READING_SURNAMES[surnameKanji]
            ?: listOf(surnameKanji to "カスタム入力")
        val givenReadings = if (givenKanji.isEmpty()) {
            listOf("")
        } else {
            COMMON_GIVEN_NAMES[givenKanji] ?: listOf("タロウ")
        }

        val options = mutableListOf<ReadingOption>()
        for ((sReading, sNote) in surnamePairs) {
            for (gReading in givenReadings) {
                val full = if (gReading.isNotEmpty()) "$sReading $gReading" else sReading
                options.add(
                    ReadingOption(
                        fullReading = full,
                        surnameReading = sReading,
                        note = sNote
                    )
                )
            }
        }
        return options
    }
}
