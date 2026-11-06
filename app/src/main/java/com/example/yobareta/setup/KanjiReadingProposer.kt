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
    val fullReading: String,      // e.g. "ショウジ タロウ" or "AH-leks SMITH"
    val surnameReading: String,   // e.g. "ショウジ" or "SMITH"
    val note: String = ""         // e.g. "一般的" or "Phonetic"
)

object KanjiReadingProposer {
    private const val TAG = "KanjiReadingProposer"

    // Curated instant lookup for multi-reading & common Japanese surnames
    private val MULTI_READING_SURNAMES: Map<String, List<Pair<String, String>>> = mapOf(
        "東海林" to listOf("ショウジ" to "一般的", "トウカイリン" to "別読み"),
        "小鳥遊" to listOf("タカナシ" to "難読姓"),
        "四月一日" to listOf("ワタヌキ" to "難読姓", "ツボミ" to "別読み"),
        "月見里" to listOf("ヤマナシ" to "難読姓"),
        "神谷" to listOf("カミヤ" to "一般的", "カミタニ" to "関西など"),
        "山崎" to listOf("ヤマザキ" to "東日本", "ヤマサキ" to "西日本"),
        "中島" to listOf("ナカジマ" to "一般的", "ナカシマ" to "西日本"),
        "河野" to listOf("コウノ" to "一般的", "カワノ" to "別読み"),
        "大野" to listOf("オオノ" to "オーノ"),
        "佐藤" to listOf("サトウ" to "サトー"),
        "鈴木" to listOf("スズキ" to "標準"),
        "高橋" to listOf("タカハシ" to "標準"),
        "田中" to listOf("タナカ" to "標準"),
        "渡辺" to listOf("ワタナベ" to "標準"),
        "伊藤" to listOf("イトウ" to "イトー"),
        "山本" to listOf("ヤマモト" to "標準"),
        "中村" to listOf("ナカムラ" to "標準"),
        "小林" to listOf("コバヤシ" to "標準"),
        "加藤" to listOf("カトウ" to "カトー"),
        "吉田" to listOf("ヨシダ" to "標準"),
        "山田" to listOf("ヤマダ" to "標準"),
        "佐々木" to listOf("ササキ" to "標準"),
        "山口" to listOf("ヤマグチ" to "標準"),
        "松本" to listOf("マツモト" to "標準"),
        "井上" to listOf("イノウエ" to "標準"),
        "木村" to listOf("キムラ" to "標準"),
        "林" to listOf("ハヤシ" to "標準"),
        "斎藤" to listOf("サイトウ" to "サイトー")
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
        "直樹" to listOf("ナオキ"),
        "さくら" to listOf("サクラ"),
        "蓮" to listOf("レン"),
        "陽翔" to listOf("ハルト")
    )

    // Curated phonetic respellings for common English / international names
    private val ENGLISH_TO_PHONETIC: Map<String, String> = mapOf(
        "alex" to "AH-leks",
        "alexander" to "ah-leg-ZAN-der",
        "smith" to "SMITH",
        "john" to "JON",
        "johnson" to "JON-sun",
        "michael" to "MY-kul",
        "david" to "DAY-vid",
        "sarah" to "SAIR-uh",
        "emily" to "EM-ih-lee",
        "james" to "JAYMZ",
        "robert" to "RAH-burt",
        "william" to "WIL-yum",
        "stephen" to "STEE-ven",
        "steven" to "STEE-ven",
        "sean" to "SHAWN",
        "shaun" to "SHAWN",
        "chloe" to "KLOH-ee",
        "zoe" to "ZOH-ee",
        "rachel" to "RAY-chul",
        "daniel" to "DAN-yul",
        "matthew" to "MATH-yoo",
        "andrew" to "AN-droo",
        "jessica" to "JES-ih-kuh",
        "jennifer" to "JEN-ih-fur",
        "amanda" to "uh-MAN-duh",
        "chris" to "KRIS",
        "christopher" to "KRIS-tuh-fur",
        "kevin" to "KEV-in",
        "brian" to "BRY-un",
        "jason" to "JAY-sun",
        "eric" to "AIR-ik",
        "anna" to "AH-nuh",
        "maria" to "muh-REE-uh",
        "carlos" to "KAR-lohs",
        "brown" to "BROWN",
        "davis" to "DAY-vis",
        "miller" to "MIL-ur",
        "wilson" to "WIL-sun",
        "taylor" to "TAY-lur",
        "thomas" to "TAH-mus",
        "lee" to "LEE",
        "chen" to "CHEN",
        "wang" to "WAHNG",
        "kim" to "KIM",
        "park" to "PARK",
        "hevidu" to "HEH-vee-doo",
        "shoji" to "SHOH-jee",
        "taro" to "TAH-roh",
        "sato" to "SAH-toh",
        "suzuki" to "soo-ZOO-kee",
        "tanaka" to "tah-NAH-kah",
        "yamamoto" to "yah-mah-MOH-toh",
        "watanabe" to "wah-tah-NAH-beh"
    )

    // Curated & rule-based English/Romaji -> Katakana for Japanese PA announcements
    private val ENGLISH_TO_KATAKANA: Map<String, String> = mapOf(
        "alex" to "アレックス",
        "alexander" to "アレクサンダー",
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
        "stephen" to "スティーブン",
        "steven" to "スティーブン",
        "sean" to "ショーン",
        "shaun" to "ショーン",
        "chloe" to "クロエ",
        "rachel" to "レイチェル",
        "daniel" to "ダニエル",
        "matthew" to "マシュー",
        "andrew" to "アンドリュー",
        "chris" to "クリス",
        "kevin" to "ケビン",
        "brian" to "ブライアン",
        "jason" to "ジェイソン",
        "eric" to "エリック",
        "anna" to "アンナ",
        "maria" to "マリア",
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
        "hevidu" to "ヘビドゥ",
        "shoji" to "ショウジ",
        "taro" to "タロウ",
        "sato" to "サトウ",
        "suzuki" to "スズキ",
        "tanaka" to "タナカ",
        "yamamoto" to "ヤマモト",
        "watanabe" to "ワタナベ"
    )

    /**
     * Instant (<1ms) local reading generator called on every keystroke so readings
     * update live without waiting for network calls.
     */
    fun proposeInstantLocalReadings(nameInput: String): List<ReadingOption> {
        val cleaned = nameInput.trim()
        if (cleaned.isEmpty()) return emptyList()
        return buildLocalFallback(cleaned)
    }

    /**
     * Enriches reading options asynchronously using Gemini via Firebase AI Logic.
     */
    suspend fun proposeReadings(nameInput: String): List<ReadingOption> = withContext(Dispatchers.IO) {
        val cleaned = nameInput.trim()
        if (cleaned.isEmpty()) return@withContext emptyList()

        val localSuggestions = buildLocalFallback(cleaned)
        val isEnglishInput = cleaned.any { it in 'A'..'Z' || it in 'a'..'z' }

        try {
            withTimeout(3500L) {
                val model = Firebase.vertexAI.generativeModel(
                    modelName = "gemini-2.0-flash",
                    generationConfig = generationConfig {
                        temperature = 0.1f
                        maxOutputTokens = 256
                        responseMimeType = "application/json"
                    }
                )
                val prompt = if (isEnglishInput) {
                    """
                    For the personal name "$cleaned", propose:
                    1. Its English phonetic pronunciation respelling (syllables separated by hyphens, e.g. "AH-leks SMITH" or "HEH-vee-doo").
                    2. Its Japanese Katakana pronunciation as spoken over a Japanese PA system (e.g. "アレックス スミス").
                    Return JSON: {"readings": [
                      {"full_reading": "AH-leks SMITH", "surname_reading": "SMITH", "note": "Phonetic"},
                      {"full_reading": "アレックス スミス", "surname_reading": "スミス", "note": "Japanese PA"}
                    ]}
                    """.trimIndent()
                } else {
                    """
                    Propose all common Japanese Katakana readings and English phonetic respelling for the Japanese personal name "$cleaned".
                    Include multiple readings if the surname has ambiguous readings (e.g. 東海林 -> ショウジ and トウカイリン).
                    Return JSON: {"readings": [
                      {"full_reading": "ショウジ タロウ", "surname_reading": "ショウジ", "note": "一般的"},
                      {"full_reading": "トウカイリン タロウ", "surname_reading": "トウカイリン", "note": "別読み"},
                      {"full_reading": "SHOH-jee TAH-roh", "surname_reading": "SHOH-jee", "note": "Phonetic"}
                    ]}
                    """.trimIndent()
                }

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
            Log.d(TAG, "Using instant local phonetic/katakana readings: ${e.message}")
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

    private fun wordToPhoneticSpeak(word: String): String {
        val clean = word.lowercase().replace(Regex("[^a-z]"), "")
        if (clean.isEmpty()) return word
        ENGLISH_TO_PHONETIC[clean]?.let { return it }

        // Rule-based phonetic respelling for arbitrary English/international names
        var p = clean
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("qu", "kw")
            .replace("tion", "-shun")
            .replace("sion", "-zhun")
            .replace(Regex("c([eiy])"), "s$1")
            .replace("ch", "ch")
            .replace("sh", "sh")
            .replace("th", "th")

        // Split into vowel-anchored phonetic syllables (e.g. "hevidu" -> "HEH-vee-doo")
        val syllables = mutableListOf<String>()
        val regex = Regex("[^aeiouy]*[aeiouy]+(?:[^aeiouy]+(?=$|[^aeiouy][aeiouy]))?")
        val matches = regex.findAll(p).map { it.value }.toList()
        val parts = if (matches.isNotEmpty()) matches else listOf(p)

        for ((idx, rawSyl) in parts.withIndex()) {
            val formatted = rawSyl
                .replace(Regex("^([bcdfghjklmnpqrstvwxz]*)i$"), "$1ee")
                .replace(Regex("^([bcdfghjklmnpqrstvwxz]*)u$"), "$1oo")
                .replace(Regex("^([bcdfghjklmnpqrstvwxz]*)a$"), "$1ah")
                .replace(Regex("^([bcdfghjklmnpqrstvwxz]*)o$"), "$1oh")
                .replace(Regex("^([bcdfghjklmnpqrstvwxz]*)e$"), "$1eh")
            syllables.add(if (idx == 0) formatted.uppercase() else formatted.lowercase())
        }
        return syllables.joinToString("-")
    }

    private fun wordToKatakana(word: String): String {
        val clean = word.lowercase().replace(Regex("[^a-z]"), "")
        if (clean.isEmpty()) return ""
        ENGLISH_TO_KATAKANA[clean]?.let { return it }

        // Lightweight Romaji/English -> Katakana converter
        val romajiMap = listOf(
            "sha" to "シャ", "shi" to "シ", "shu" to "シュ", "she" to "シェ", "sho" to "ショ",
            "cha" to "チャ", "chi" to "チ", "chu" to "チュ", "che" to "チェ", "cho" to "チョ",
            "tsu" to "ツ", "tha" to "サ", "thi" to "ティ", "thu" to "ス", "the" to "ゼ", "tho" to "ソ",
            "fa" to "ファ", "fi" to "フィ", "fu" to "フ", "fe" to "フェ", "fo" to "フォ",
            "va" to "ヴァ", "vi" to "ヴィ", "vu" to "ヴ", "ve" to "ヴェ", "vo" to "ヴォ",
            "ka" to "カ", "ki" to "キ", "ku" to "ク", "ke" to "ケ", "ko" to "コ",
            "sa" to "サ", "si" to "シ", "su" to "ス", "se" to "セ", "so" to "ソ",
            "ta" to "タ", "ti" to "ティ", "tu" to "トゥ", "te" to "テ", "to" to "ト",
            "na" to "ナ", "ni" to "ニ", "nu" to "ヌ", "ne" to "ネ", "no" to "ノ",
            "ha" to "ハ", "hi" to "ヒ", "hu" to "フ", "he" to "ヘ", "ho" to "ホ",
            "ma" to "マ", "mi" to "ミ", "mu" to "ム", "me" to "メ", "mo" to "モ",
            "ya" to "ヤ", "yu" to "ユ", "yo" to "ヨ",
            "ra" to "ラ", "ri" to "リ", "ru" to "ル", "re" to "レ", "ro" to "ロ",
            "la" to "ラ", "li" to "リ", "lu" to "ル", "le" to "レ", "lo" to "ロ",
            "wa" to "ワ", "wi" to "ウィ", "we" to "ウェ", "wo" to "ウォ",
            "ga" to "ガ", "gi" to "ギ", "gu" to "グ", "ge" to "ゲ", "go" to "ゴ",
            "za" to "ザ", "zi" to "ジ", "zu" to "ズ", "ze" to "ゼ", "zo" to "ゾ",
            "ja" to "ジャ", "ji" to "ジ", "ju" to "ジュ", "je" to "ジェ", "jo" to "ジョ",
            "da" to "ダ", "di" to "ディ", "du" to "ドゥ", "de" to "デ", "do" to "ド",
            "ba" to "バ", "bi" to "ビ", "bu" to "ブ", "be" to "ベ", "bo" to "ボ",
            "pa" to "パ", "pi" to "ピ", "pu" to "プ", "pe" to "ペ", "po" to "ポ",
            "ca" to "カ", "ci" to "シ", "cu" to "キュ", "ce" to "セ", "co" to "コ",
            "a" to "ア", "i" to "イ", "u" to "ウ", "e" to "エ", "o" to "オ",
            "n" to "ン", "m" to "ム", "s" to "ス", "k" to "ク", "t" to "ト",
            "r" to "ル", "l" to "ル", "d" to "ド", "g" to "グ", "b" to "ブ", "p" to "プ", "x" to "ックス"
        )
        val sb = StringBuilder()
        var i = 0
        while (i < clean.length) {
            var matched = false
            for ((rom, kana) in romajiMap) {
                if (clean.startsWith(rom, i)) {
                    sb.append(kana)
                    i += rom.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                i++
            }
        }
        return sb.toString()
    }

    private fun hiraganaToKatakana(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch in '\u3041'..'\u3096') {
                sb.append((ch.code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun buildLocalFallback(nameInput: String): List<ReadingOption> {
        val normalized = nameInput.replace("　", " ").trim()
        val hasLatin = normalized.any { it in 'A'..'Z' || it in 'a'..'z' }

        if (hasLatin) {
            val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val phoneticWords = words.map { wordToPhoneticSpeak(it) }
            val surnameWord = if (words.size >= 2) words.last() else words.first()
            val surnamePhonetic = wordToPhoneticSpeak(surnameWord)

            val options = mutableListOf(
                ReadingOption(
                    fullReading = phoneticWords.joinToString(" "),
                    surnameReading = surnamePhonetic,
                    note = "Phonetic"
                )
            )

            val kataWords = words.map { wordToKatakana(it) }.filter { it.isNotEmpty() }
            if (kataWords.isNotEmpty()) {
                val kataSurname = wordToKatakana(surnameWord).ifEmpty { kataWords.last() }
                options.add(
                    ReadingOption(
                        fullReading = kataWords.joinToString(" "),
                        surnameReading = kataSurname,
                        note = "Japanese PA"
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
            ?: listOf(hiraganaToKatakana(surnameKanji) to "標準")
        val givenReadings = if (givenKanji.isEmpty()) {
            listOf("")
        } else {
            COMMON_GIVEN_NAMES[givenKanji] ?: listOf(hiraganaToKatakana(givenKanji))
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
