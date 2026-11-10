package com.example.yobareta.setup

import android.util.Log
import com.example.yobareta.AppConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.vertexai.type.content
import com.google.firebase.vertexai.type.generationConfig
import com.google.firebase.vertexai.vertexAI
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
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

    // Curated instant lookup for multi-reading & common Japanese surnames (200+ entries)
    private val MULTI_READING_SURNAMES: Map<String, List<Pair<String, String>>> = mapOf(
        "東海林" to listOf("ショウジ" to "一般的", "トウカイリン" to "別読み"),
        "五条" to listOf("ゴジョウ" to "標準"),
        "うずまき" to listOf("ウズマキ" to "標準"),
        "渦巻" to listOf("ウズマキ" to "標準"),
        "小鳥遊" to listOf("タカナシ" to "難読姓"),
        "四月一日" to listOf("ワタヌキ" to "難読姓", "ツボミ" to "別読み"),
        "月見里" to listOf("ヤマナシ" to "難読姓"),
        "勅使河原" to listOf("テシガワラ" to "難読姓"),
        "百目鬼" to listOf("ドウメキ" to "難読姓"),
        "伊集院" to listOf("イジュウイン" to "標準"),
        "西園寺" to listOf("サイオンジ" to "標準"),
        "早乙女" to listOf("サオトメ" to "標準"),
        "五月女" to listOf("サオトメ" to "標準", "ソオトメ" to "別読み"),
        "五十嵐" to listOf("イガラシ" to "標準", "イカラシ" to "別読み"),
        "長谷川" to listOf("ハセガワ" to "標準"),
        "佐々木" to listOf("ササキ" to "標準"),
        "大久保" to listOf("オオクボ" to "標準"),
        "久保田" to listOf("クボタ" to "標準"),
        "小野寺" to listOf("オノデラ" to "標準"),
        "宇都宮" to listOf("ウツノミヤ" to "標準"),
        "神谷" to listOf("カミヤ" to "一般的", "カミタニ" to "関西など"),
        "山崎" to listOf("ヤマザキ" to "東日本", "ヤマサキ" to "西日本"),
        "山﨑" to listOf("ヤマザキ" to "東日本", "ヤマサキ" to "西日本"),
        "中島" to listOf("ナカジマ" to "一般的", "ナカシマ" to "西日本"),
        "中嶋" to listOf("ナカジマ" to "一般的", "ナカシマ" to "西日本"),
        "河野" to listOf("コウノ" to "一般的", "カワノ" to "別読み"),
        "大野" to listOf("オオノ" to "標準"),
        "佐藤" to listOf("サトウ" to "標準"),
        "鈴木" to listOf("スズキ" to "標準"),
        "高橋" to listOf("タカハシ" to "標準"),
        "髙橋" to listOf("タカハシ" to "標準"),
        "田中" to listOf("タナカ" to "標準"),
        "渡辺" to listOf("ワタナベ" to "標準"),
        "渡邉" to listOf("ワタナベ" to "標準"),
        "渡邊" to listOf("ワタナベ" to "標準"),
        "伊藤" to listOf("イトウ" to "標準"),
        "山本" to listOf("ヤマモト" to "標準"),
        "中村" to listOf("ナカムラ" to "標準"),
        "小林" to listOf("コバヤシ" to "標準"),
        "加藤" to listOf("カトウ" to "標準"),
        "吉田" to listOf("ヨシダ" to "標準"),
        "山田" to listOf("ヤマダ" to "標準"),
        "山口" to listOf("ヤマグチ" to "標準"),
        "松本" to listOf("マツモト" to "標準"),
        "井上" to listOf("イノウエ" to "標準"),
        "木村" to listOf("キムラ" to "標準"),
        "林" to listOf("ハヤシ" to "標準"),
        "斎藤" to listOf("サイトウ" to "標準"),
        "斉藤" to listOf("サイトウ" to "標準"),
        "齋藤" to listOf("サイトウ" to "標準"),
        "齊藤" to listOf("サイトウ" to "標準"),
        "清水" to listOf("シミズ" to "標準"),
        "山中" to listOf("ヤマナカ" to "標準"),
        "山下" to listOf("ヤマシタ" to "標準"),
        "森" to listOf("モリ" to "標準"),
        "池田" to listOf("イケダ" to "標準"),
        "橋本" to listOf("ハシモト" to "標準"),
        "阿部" to listOf("アベ" to "標準"),
        "安倍" to listOf("アベ" to "標準"),
        "石川" to listOf("イシカワ" to "標準"),
        "前田" to listOf("マエダ" to "標準"),
        "藤田" to listOf("フジタ" to "標準"),
        "小川" to listOf("オガワ" to "標準"),
        "後藤" to listOf("ゴトウ" to "標準"),
        "岡田" to listOf("オカダ" to "標準"),
        "村上" to listOf("ムラカミ" to "標準"),
        "近藤" to listOf("コンドウ" to "標準"),
        "石井" to listOf("イシイ" to "標準"),
        "坂本" to listOf("サカモト" to "標準"),
        "遠藤" to listOf("エンドウ" to "標準"),
        "青木" to listOf("アオキ" to "標準"),
        "藤井" to listOf("フジイ" to "標準"),
        "西村" to listOf("ニシムラ" to "標準"),
        "福田" to listOf("フクダ" to "標準"),
        "太田" to listOf("オオタ" to "標準"),
        "三浦" to listOf("ミウラ" to "標準"),
        "藤原" to listOf("フジワラ" to "一般的", "フジハラ" to "別読み"),
        "岡本" to listOf("オカモト" to "標準"),
        "松田" to listOf("マツダ" to "一般的", "マツタ" to "別読み"),
        "中川" to listOf("ナカガワ" to "標準"),
        "中野" to listOf("ナカノ" to "標準"),
        "原田" to listOf("ハラダ" to "標準"),
        "小野" to listOf("オノ" to "標準"),
        "田村" to listOf("タムラ" to "標準"),
        "竹内" to listOf("タケウチ" to "標準"),
        "金子" to listOf("カネコ" to "標準"),
        "和田" to listOf("ワダ" to "標準"),
        "中山" to listOf("ナカヤマ" to "標準"),
        "石田" to listOf("イシダ" to "標準"),
        "上田" to listOf("ウエダ" to "標準"),
        "森田" to listOf("モリタ" to "標準"),
        "原" to listOf("ハラ" to "標準"),
        "柴田" to listOf("シバタ" to "標準"),
        "酒井" to listOf("サカイ" to "標準"),
        "坂井" to listOf("サカイ" to "標準"),
        "工藤" to listOf("クドウ" to "標準"),
        "横山" to listOf("ヨコヤマ" to "標準"),
        "宮崎" to listOf("ミヤザキ" to "一般的", "ミヤサキ" to "別読み"),
        "宮本" to listOf("ミヤモト" to "標準"),
        "内田" to listOf("ウチダ" to "標準"),
        "高木" to listOf("タカギ" to "一般的", "タカキ" to "別読み"),
        "安藤" to listOf("アンドウ" to "標準"),
        "谷口" to listOf("タニグチ" to "標準"),
        "丸山" to listOf("マルヤマ" to "標準"),
        "今井" to listOf("イマイ" to "標準"),
        "高田" to listOf("タカダ" to "一般的", "タカタ" to "別読み"),
        "武田" to listOf("タケダ" to "標準"),
        "村田" to listOf("ムラタ" to "標準"),
        "上野" to listOf("ウエノ" to "標準"),
        "杉山" to listOf("スギヤマ" to "標準"),
        "増田" to listOf("マスダ" to "標準"),
        "平野" to listOf("ヒラノ" to "標準"),
        "大塚" to listOf("オオツカ" to "標準"),
        "千葉" to listOf("チバ" to "標準"),
        "久保" to listOf("クボ" to "標準"),
        "松井" to listOf("マツイ" to "標準"),
        "小山" to listOf("コヤマ" to "一般的", "オヤマ" to "別読み"),
        "岩崎" to listOf("イワサキ" to "一般的", "イワザキ" to "別読み"),
        "桜井" to listOf("サクライ" to "標準"),
        "櫻井" to listOf("サクライ" to "標準"),
        "野口" to listOf("ノグチ" to "標準"),
        "松尾" to listOf("マツオ" to "標準"),
        "野村" to listOf("ノムラ" to "標準"),
        "木下" to listOf("キノシタ" to "標準"),
        "菊地" to listOf("キクチ" to "標準"),
        "菊池" to listOf("キクチ" to "標準"),
        "佐野" to listOf("サノ" to "標準"),
        "大西" to listOf("オオニシ" to "標準"),
        "杉本" to listOf("スギモト" to "標準"),
        "新井" to listOf("アライ" to "標準"),
        "荒井" to listOf("アライ" to "標準"),
        "浜田" to listOf("ハマダ" to "標準"),
        "濱田" to listOf("ハマダ" to "標準"),
        "菅原" to listOf("スガワラ" to "一般的", "スガハラ" to "別読み"),
        "市川" to listOf("イチカワ" to "標準"),
        "水野" to listOf("ミズノ" to "標準"),
        "小松" to listOf("コマツ" to "標準"),
        "高野" to listOf("タカノ" to "一般的", "コウノ" to "別読み"),
        "吉川" to listOf("ヨシカワ" to "一般的", "キッカワ" to "別読み"),
        "山内" to listOf("ヤマウチ" to "標準"),
        "西田" to listOf("ニシダ" to "標準"),
        "北村" to listOf("キタムラ" to "標準"),
        "服部" to listOf("ハットリ" to "標準"),
        "安田" to listOf("ヤスダ" to "標準"),
        "中田" to listOf("ナカタ" to "一般的", "ナカダ" to "別読み"),
        "川口" to listOf("カワグチ" to "標準"),
        "平田" to listOf("ヒラタ" to "標準"),
        "川崎" to listOf("カワサキ" to "一般的", "カワザキ" to "別読み"),
        "飯田" to listOf("イイダ" to "標準"),
        "東" to listOf("ヒガシ" to "一般的", "アズマ" to "別読み"),
        "本田" to listOf("ホンダ" to "標準"),
        "沢田" to listOf("サワダ" to "標準"),
        "澤田" to listOf("サワダ" to "標準"),
        "吉村" to listOf("ヨシムラ" to "標準"),
        "中西" to listOf("ナカニシ" to "標準"),
        "関" to listOf("セキ" to "標準"),
        "辻" to listOf("ツジ" to "標準"),
        "樋口" to listOf("ヒグチ" to "標準"),
        "福島" to listOf("フクシマ" to "標準"),
        "川上" to listOf("カワカミ" to "標準"),
        "永井" to listOf("ナガイ" to "標準"),
        "松岡" to listOf("マツオカ" to "標準"),
        "田口" to listOf("タグチ" to "標準"),
        "森本" to listOf("モリモト" to "標準"),
        "矢野" to listOf("ヤノ" to "標準"),
        "秋山" to listOf("アキヤマ" to "標準"),
        "馬場" to listOf("ババ" to "標準"),
        "熊谷" to listOf("クマガイ" to "一般的", "クマタニ" to "別読み"),
        "松浦" to listOf("マツウラ" to "標準"),
        "小池" to listOf("コイケ" to "標準"),
        "浅野" to listOf("アサノ" to "標準"),
        "星野" to listOf("ホシノ" to "標準"),
        "黒田" to listOf("クロダ" to "標準"),
        "堀" to listOf("ホリ" to "標準"),
        "川村" to listOf("カワムラ" to "標準"),
        "望月" to listOf("モチヅキ" to "標準"),
        "荒木" to listOf("アラキ" to "標準"),
        "内藤" to listOf("ナイトウ" to "標準"),
        "菅野" to listOf("カンノ" to "一般的", "スガノ" to "別読み"),
        "西山" to listOf("ニシヤマ" to "標準"),
        "大谷" to listOf("オオタニ" to "一般的", "オオヤ" to "別読み"),
        "平井" to listOf("ヒライ" to "標準"),
        "大島" to listOf("オオシマ" to "標準"),
        "岩田" to listOf("イワタ" to "標準"),
        "広瀬" to listOf("ヒロセ" to "標準"),
        "廣瀬" to listOf("ヒロセ" to "標準"),
        "石橋" to listOf("イシバシ" to "標準"),
        "横田" to listOf("ヨコタ" to "標準"),
        "岡崎" to listOf("オカザキ" to "標準"),
        "鎌田" to listOf("カマタ" to "一般的", "カマダ" to "別読み"),
        "片山" to listOf("カタヤマ" to "標準"),
        "関口" to listOf("セキグチ" to "標準"),
        "本間" to listOf("ホンマ" to "標準"),
        "須藤" to listOf("スドウ" to "一般的", "ストウ" to "別読み"),
        "岡" to listOf("オカ" to "標準"),
        "落合" to listOf("オチアイ" to "標準"),
        "鶴田" to listOf("ツルタ" to "標準"),
        "鬼頭" to listOf("キトウ" to "標準")
    )

    private val COMMON_GIVEN_NAMES: Map<String, List<String>> = mapOf(
        "太郎" to listOf("タロウ"),
        "悟" to listOf("サトル"),
        "ナルト" to listOf("ナルト"),
        "鳴門" to listOf("ナルト"),
        "一郎" to listOf("イチロウ"),
        "次郎" to listOf("ジロウ"),
        "二郎" to listOf("ジロウ"),
        "三郎" to listOf("サブロウ"),
        "花子" to listOf("ハナコ"),
        "健太" to listOf("ケンタ"),
        "大輔" to listOf("ダイスケ"),
        "翔太" to listOf("ショウタ"),
        "翔" to listOf("ショウ", "カケル"),
        "美咲" to listOf("ミサキ"),
        "陽菜" to listOf("ヒナ", "ハルナ"),
        "誠" to listOf("マコト"),
        "直樹" to listOf("ナオキ"),
        "さくら" to listOf("サクラ"),
        "桜" to listOf("サクラ"),
        "蓮" to listOf("レン"),
        "陽翔" to listOf("ハルト"),
        "湊" to listOf("ミナト"),
        "樹" to listOf("イツキ", "タツキ"),
        "大和" to listOf("ヤマト"),
        "悠真" to listOf("ユウマ", "ハルマ"),
        "陽太" to listOf("ヨウタ", "ヒナタ"),
        "朝陽" to listOf("アサヒ"),
        "颯太" to listOf("ソウタ"),
        "新" to listOf("アラタ", "シン"),
        "拓海" to listOf("タクミ"),
        "拓実" to listOf("タクミ"),
        "匠" to listOf("タクミ"),
        "翼" to listOf("ツバサ"),
        "健" to listOf("タケル", "ケン"),
        "優" to listOf("ユウ", "マサル"),
        "勇気" to listOf("ユウキ"),
        "祐樹" to listOf("ユウキ"),
        "優希" to listOf("ユウキ"),
        "達也" to listOf("タツヤ"),
        "和也" to listOf("カズヤ"),
        "哲也" to listOf("テツヤ"),
        "智也" to listOf("トモヤ"),
        "裕太" to listOf("ユウタ"),
        "優太" to listOf("ユウタ"),
        "雄太" to listOf("ユウタ"),
        "康平" to listOf("コウヘイ"),
        "浩二" to listOf("コウジ"),
        "浩一" to listOf("コウイチ"),
        "健一" to listOf("ケンイチ"),
        "健二" to listOf("ケンジ"),
        "雄一" to listOf("ユウイチ"),
        "隆" to listOf("タカシ"),
        "豊" to listOf("ユタカ"),
        "修" to listOf("オサム"),
        "茂" to listOf("シゲル"),
        "剛" to listOf("ツヨシ", "ゴウ"),
        "亮" to listOf("リョウ"),
        "亮太" to listOf("リョウタ"),
        "涼太" to listOf("リョウタ"),
        "陸" to listOf("リク"),
        "駿" to listOf("シュン"),
        "颯" to listOf("ハヤテ", "ソウ"),
        "蒼" to listOf("アオイ", "ソウ"),
        "碧" to listOf("アオイ", "ミドリ"),
        "律" to listOf("リツ"),
        "暖" to listOf("ダン", "ハル"),
        "結翔" to listOf("ユイト"),
        "悠人" to listOf("ユウト", "ハルト"),
        "陽向" to listOf("ヒナタ"),
        "悠斗" to listOf("ユウト", "ハルト"),
        "春樹" to listOf("ハルキ"),
        "悠希" to listOf("ハルキ", "ユウキ"),
        "凛" to listOf("リン"),
        "結愛" to listOf("ユア"),
        "杏" to listOf("アン", "アンズ"),
        "紬" to listOf("ツムギ"),
        "莉子" to listOf("リコ"),
        "芽依" to listOf("メイ"),
        "葵" to listOf("アオイ"),
        "結菜" to listOf("ユイナ", "ユナ"),
        "咲良" to listOf("サラ", "サクラ"),
        "陽葵" to listOf("ヒマリ", "ヒナタ"),
        "詩" to listOf("ウタ"),
        "琴音" to listOf("コトネ"),
        "楓" to listOf("カエデ"),
        "彩" to listOf("アヤ"),
        "彩花" to listOf("アヤカ"),
        "彩乃" to listOf("アヤノ"),
        "愛" to listOf("アイ", "メグミ"),
        "愛美" to listOf("マナミ", "アイミ"),
        "舞" to listOf("マイ"),
        "麻衣" to listOf("マイ"),
        "真由美" to listOf("マユミ"),
        "恵" to listOf("メグミ", "ケイ"),
        "恵美" to listOf("エミ", "メグミ"),
        "由美" to listOf("ユミ"),
        "由美子" to listOf("ユミコ"),
        "久美子" to listOf("クミコ"),
        "智子" to listOf("トモコ"),
        "裕子" to listOf("ユウコ", "ヒロコ"),
        "洋子" to listOf("ヨウコ"),
        "京子" to listOf("キョウコ"),
        "幸子" to listOf("サチコ"),
        "和子" to listOf("カズコ"),
        "明美" to listOf("アケミ"),
        "香織" to listOf("カオリ"),
        "沙織" to listOf("サオリ"),
        "千尋" to listOf("チヒロ"),
        "千夏" to listOf("チナツ"),
        "明日香" to listOf("アスカ"),
        "未来" to listOf("ミライ", "ミク"),
        "美穂" to listOf("ミホ"),
        "美優" to listOf("ミユ"),
        "美羽" to listOf("ミウ"),
        "優花" to listOf("ユウカ"),
        "優奈" to listOf("ユウナ"),
        "優衣" to listOf("ユイ"),
        "結衣" to listOf("ユイ"),
        "遥" to listOf("ハルカ"),
        "遥香" to listOf("ハルカ"),
        "菜々子" to listOf("ナナコ"),
        "菜摘" to listOf("ナツミ"),
        "夏美" to listOf("ナツミ"),
        "桃子" to listOf("モモコ"),
        "七海" to listOf("ナナミ"),
        "春菜" to listOf("ハルナ"),
        "春香" to listOf("ハルカ")
    )

    // Per-character Kanji reading fallback so arbitrary Kanji names convert to Katakana immediately
    private val KANJI_CHAR_READINGS: Map<Char, String> = mapOf(
        '田' to "タ", '条' to "ジョウ", '悟' to "サトル", '渦' to "ウズ", '巻' to "マキ", '鳴' to "ナル", '中' to "ナカ", '山' to "ヤマ", '川' to "カワ", '本' to "モト",
        '村' to "ムラ", '木' to "キ", '林' to "ハヤシ", '森' to "モリ", '沢' to "サワ",
        '澤' to "サワ", '河' to "カワ", '池' to "イケ", '海' to "ウミ", '浦' to "ウラ",
        '浜' to "ハマ", '濱' to "ハマ", '島' to "シマ", '嶋' to "シマ", '崎' to "サキ",
        '﨑' to "サキ", '谷' to "タニ", '橋' to "ハシ", '原' to "ハラ", '野' to "ノ",
        '岡' to "オカ", '坂' to "サカ", '阪' to "サカ", '松' to "マツ", '竹' to "タケ",
        '梅' to "ウメ", '桜' to "サクラ", '櫻' to "サクラ", '柳' to "ヤナギ", '藤' to "フジ",
        '佐' to "サ", '鈴' to "スズ", '高' to "タカ", '髙' to "タカ", '渡' to "ワタ",
        '辺' to "ベ", '邉' to "ベ", '邊' to "ベ", '伊' to "イ", '加' to "カ",
        '吉' to "ヨシ", '口' to "グチ", '井' to "イ", '上' to "ウエ", '下' to "シタ",
        '前' to "マエ", '後' to "ゴ", '東' to "ヒガシ", '西' to "ニシ", '南' to "ミナミ",
        '北' to "キタ", '大' to "オオ", '小' to "コ", '長' to "ナガ", '新' to "シン",
        '古' to "フル", '今' to "イマ", '元' to "モト", '正' to "マサ", '真' to "マ",
        '誠' to "マコト", '実' to "ミ", '義' to "ヨシ", '仁' to "ジン", '礼' to "レイ",
        '智' to "トモ", '信' to "ノブ", '忠' to "タダ", '孝' to "タカ", '和' to "カズ",
        '平' to "ヒラ", '安' to "ヤス", '康' to "ヤス", '健' to "ケン", '強' to "ツヨ",
        '剛' to "ゴウ", '勇' to "ユウ", '雄' to "ユウ", '男' to "オ", '夫' to "オ",
        '郎' to "ロウ", '太' to "タ", '一' to "イチ", '二' to "ジ", '三' to "ゾウ",
        '四' to "シ", '五' to "ゴ", '六' to "ロク", '七' to "ナナ", '八' to "ハチ",
        '九' to "キュウ", '十' to "ジュウ", '百' to "ヒャク", '千' to "チ", '万' to "マン",
        '子' to "コ", '美' to "ミ", '花' to "カ", '華' to "カ", '菜' to "ナ",
        '奈' to "ナ", '香' to "カ", '芳' to "ヨシ", '穂' to "ホ", '咲' to "サキ",
        '笑' to "エミ", '愛' to "アイ", '恋' to "レン", '心' to "ココロ", '音' to "ネ",
        '詩' to "ウタ", '歌' to "ウタ", '舞' to "マイ", '優' to "ユウ", '良' to "リョウ",
        '佳' to "カ", '善' to "ゼン", '喜' to "キ", '幸' to "サチ", '福' to "フク",
        '寿' to "ヒサ", '慶' to "ケイ", '貴' to "タカ", '尊' to "タケル", '敬' to "ケイ",
        '崇' to "タカ", '隆' to "タカ", '盛' to "モリ", '栄' to "エイ", '豊' to "ユタカ",
        '裕' to "ユウ", '富' to "トミ", '宝' to "タカラ", '光' to "ヒカリ", '明' to "アキ",
        '輝' to "テル", '晃' to "アキラ", '照' to "テル", '陽' to "ヨウ", '日' to "ヒ",
        '月' to "ツキ", '星' to "ホシ", '空' to "ソラ", '天' to "テン", '風' to "カゼ",
        '雲' to "クモ", '雨' to "アメ", '雪' to "ユキ", '春' to "ハル", '夏' to "ナツ",
        '秋' to "アキ", '冬' to "フユ", '朝' to "アサ", '夕' to "ユウ", '夜' to "ヨ",
        '金' to "カナ", '銀' to "ギン", '鉄' to "テツ", '玉' to "タマ", '珠' to "タマ",
        '瑠' to "ル", '璃' to "リ", '碧' to "アオイ", '緑' to "ミドリ", '青' to "アオ",
        '蒼' to "アオ", '藍' to "アイ", '紅' to "ベニ", '茜' to "アカネ", '紫' to "ムラサキ",
        '白' to "シロ", '黒' to "クロ", '黄' to "キ", '石' to "イシ", '岩' to "イワ",
        '砂' to "スナ", '土' to "ツチ", '地' to "チ", '里' to "サト", '郷' to "ゴウ",
        '町' to "マチ", '都' to "ミヤコ", '京' to "キョウ", '国' to "クニ", '宮' to "ミヤ",
        '社' to "ヤシロ", '寺' to "テラ", '院' to "イン", '堂' to "ドウ", '城' to "シロ",
        '館' to "ヤカタ", '家' to "イエ", '屋' to "ヤ", '室' to "ムロ", '戸' to "ト",
        '門' to "カド", '庭' to "ニワ", '園' to "ソノ", '畑' to "ハタ", '畠' to "ハタ",
        '翔' to "ショウ", '蓮' to "レン", '湊' to "ミナト", '樹' to "キ", '悠' to "ユウ",
        '颯' to "ソウ", '拓' to "タク", '翼' to "ツバサ", '達' to "タツ", '也' to "ヤ",
        '哲' to "テツ", '浩' to "コウ", '修' to "オサム", '茂' to "シゲル", '亮' to "リョウ",
        '涼' to "リョウ", '陸' to "リク", '駿' to "シュン", '律' to "リツ", '暖' to "ダン",
        '結' to "ユイ", '向' to "ヒナ", '斗' to "ト", '希' to "キ", '凛' to "リン",
        '杏' to "アン", '紬' to "ツムギ", '莉' to "リ", '芽' to "メ", '依' to "イ",
        '葵' to "アオイ", '琴' to "コト", '楓' to "カエデ", '彩' to "アヤ",
        '乃' to "ノ", '麻' to "マ", '衣' to "イ", '由' to "ユ", '恵' to "エ",
        '久' to "ク", '洋' to "ヨウ", '織' to "オリ", '沙' to "サ", '尋' to "ヒロ",
        '未' to "ミ", '来' to "ライ", '羽' to "ウ", '遥' to "ハルカ", '摘' to "ツミ",
        '桃' to "モモ", '輔' to "スケ", '介' to "スケ", '助' to "スケ", '之' to "ユキ",
        '史' to "フミ", '文' to "フミ", '司' to "ジ", '治' to "ジ", '次' to "ジ",
        '広' to "ヒロ", '廣' to "ヒロ", '博' to "ヒロ", '宏' to "ヒロ", '弘' to "ヒロ",
        '寛' to "ヒロ", '聡' to "サトシ", '敏' to "トシ", '俊' to "トシ", '利' to "トシ",
        '秀' to "ヒデ", '英' to "ヒデ", '勝' to "マサル", '克' to "カツ", '将' to "マサ",
        '雅' to "マサ", '昌' to "マサ", '政' to "マサ", '成' to "ナリ", '昭' to "アキ",
        '章' to "アキ", '彰' to "アキ", '典' to "ノリ", '則' to "ノリ",
        '紀' to "ノリ", '憲' to "ケン", '謙' to "ケン", '賢' to "ケン", '研' to "ケン"
    )

    // Curated phonetic respellings for common English / international names
    private val ENGLISH_TO_PHONETIC: Map<String, String> = mapOf(
        "alex" to "AH-leks",
        "harry" to "HAIR-ee",
        "potter" to "PAH-tur",
        "gojou" to "GOH-joh",
        "gojo" to "GOH-joh",
        "satoru" to "sah-TOH-roo",
        "naruto" to "NAH-roo-toh",
        "uzumaki" to "oo-zoo-MAH-kee",
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
        "harry" to "ハリー",
        "potter" to "ポッター",
        "gojou" to "ゴジョウ",
        "gojo" to "ゴジョウ",
        "satoru" to "サトル",
        "naruto" to "ナルト",
        "uzumaki" to "ウズマキ",
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

    private val HYPHENATED_SYLLABLES = Regex("[A-Za-z]-[A-Za-z]")

    /**
     * True for display-only pronunciation guides such as "AH-leks SMITH" or "SHOH-jee".
     * These are not real words, so they must never be sent to the backend as STT
     * phrase hints or matcher targets.
     */
    fun isPhoneticRespelling(text: String, note: String = ""): Boolean {
        if (note.contains("phonetic", ignoreCase = true)) return true
        val trimmed = text.trim()
        if (trimmed.none { it in 'A'..'Z' || it in 'a'..'z' }) return false
        if (HYPHENATED_SYLLABLES.containsMatchIn(trimmed)) return true
        val letters = trimmed.filter { it.isLetter() }
        return letters.length > 1 && letters.all { it.isUpperCase() }
    }

    /**
     * Builds the real-word readings sent to the backend (STT phrase hints + matcher targets).
     * Phonetic respellings are excluded (display-only).
     */
    fun buildMatchHints(
        nameInput: String,
        options: List<ReadingOption>
    ): Pair<List<String>, List<String>> {
        val name = nameInput.replace("　", " ").trim()
        val nameIsLatin = name.any { it in 'A'..'Z' || it in 'a'..'z' }
        val fullHints = mutableListOf<String>()
        val surnameHints = mutableListOf<String>()

        if (nameIsLatin) {
            fullHints.add(name)
            name.split(Regex("\\s+")).lastOrNull()?.let { surnameHints.add(it) }
        }

        for (option in options) {
            if (isPhoneticRespelling(option.fullReading, option.note)) continue
            fullHints.add(option.fullReading)
            surnameHints.add(option.surnameReading)

            if (!nameIsLatin) {
                katakanaToRomaji(option.fullReading)?.let { romaji ->
                    fullHints.add(romaji)
                    val words = romaji.split(" ")
                    if (words.size == 2) fullHints.add("${words[1]} ${words[0]}")
                }
                katakanaToRomaji(option.surnameReading)?.let { surnameHints.add(it) }
            }
        }

        return fullHints.filter { it.isNotBlank() }.distinct() to
            surnameHints.filter { it.isNotBlank() }.distinct()
    }

    fun buildGivenNameHints(
        nameInput: String,
        options: List<ReadingOption>
    ): List<String> {
        val name = nameInput.replace("　", " ").trim()
        val nameIsLatin = name.any { it in 'A'..'Z' || it in 'a'..'z' }
        val givenHints = mutableListOf<String>()

        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size >= 2) {
            givenHints.add(if (nameIsLatin) words.first() else words.last())
        }

        for (option in options) {
            if (isPhoneticRespelling(option.fullReading, option.note)) continue
            val fClean = option.fullReading.replace("　", " ").trim()
            val sClean = option.surnameReading.replace("　", " ").trim()
            val gReading = fClean.replace(sClean, "").trim()
            if (gReading.isNotEmpty()) {
                givenHints.add(gReading)
                if (!nameIsLatin) {
                    katakanaToRomaji(gReading)?.let { givenHints.add(it) }
                }
            }
        }
        return givenHints.filter { it.isNotBlank() }.distinct()
    }

    private val KATAKANA_DIGRAPHS: Map<String, String> = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho", "シェ" to "she",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "チェ" to "che",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ジェ" to "je",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
        "ティ" to "ti", "ディ" to "di", "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo"
    )

    private val KATAKANA_SINGLES: Map<Char, String> = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヲ' to "o", 'ン' to "n", 'ヴ' to "vu",
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o"
    )

    fun katakanaToRomaji(text: String): String? {
        val kana = hiraganaToKatakana(text.replace("　", " ").trim())
        if (kana.isEmpty()) return null
        if (kana.any { !(it in '\u30A0'..'\u30FF' || it == ' ') }) return null

        val words = kana.split(Regex("[\\s・]+")).filter { it.isNotEmpty() }
        val romajiWords = words.map { word ->
            val sb = StringBuilder()
            var i = 0
            var doubleNext = false
            while (i < word.length) {
                val ch = word[i]
                if (ch == 'ッ') {
                    doubleNext = true
                    i++
                    continue
                }
                if (ch == 'ー') {
                    i++
                    continue
                }
                val pair = if (i + 1 < word.length) word.substring(i, i + 2) else ""
                val romaji = KATAKANA_DIGRAPHS[pair]?.also { i += 2 }
                    ?: KATAKANA_SINGLES[ch]?.also { i += 1 }
                    ?: run { i += 1; "" }
                if (doubleNext && romaji.isNotEmpty()) {
                    sb.append(if (romaji.startsWith("ch")) "t" else romaji.first().toString())
                    doubleNext = false
                }
                sb.append(romaji)
            }
            sb.toString().replaceFirstChar { it.uppercase() }
        }.filter { it.isNotEmpty() }

        return if (romajiWords.isEmpty()) null else romajiWords.joinToString(" ")
    }

    private fun katakanaWordToPhonetic(kataWord: String): String {
        val romaji = katakanaToRomaji(kataWord) ?: return ""
        return romaji.split(" ").filter { it.isNotEmpty() }.joinToString(" ") { wordToPhoneticSpeak(it) }
    }

    private val geminiReadingCache = mutableMapOf<String, List<ReadingOption>>()

    private fun isFullyCoveredLocally(nameInput: String): Boolean {
        val normalized = nameInput.replace("　", " ").trim()
        if (normalized.isEmpty()) return true
        val hasLatin = normalized.any { it in 'A'..'Z' || it in 'a'..'z' }
        if (hasLatin) {
            val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return words.all { w ->
                val lower = w.lowercase().replace(Regex("[^a-z]"), "")
                ENGLISH_TO_PHONETIC.containsKey(lower) && ENGLISH_TO_KATAKANA.containsKey(lower)
            }
        }
        if (normalized.all { it in '\u3040'..'\u30FF' || it == ' ' }) return true

        val (surnameKanji, givenKanji) = splitJapaneseSurnameAndGiven(normalized)
        val surnameKnown = MULTI_READING_SURNAMES.containsKey(surnameKanji)
        val givenKnown = givenKanji.isEmpty() ||
            COMMON_GIVEN_NAMES.containsKey(givenKanji) ||
            givenKanji.all { it in '\u3040'..'\u30FF' }
        return surnameKnown && givenKnown
    }

    private suspend fun fetchCloudRunReadings(
        nameInput: String,
        cloudRunBaseUrl: String
    ): List<ReadingOption> {
        val baseUrl = cloudRunBaseUrl.trim().trimEnd('/').ifEmpty {
            AppConfig.DEFAULT_CLOUD_RUN_BASE_URL.trim().trimEnd('/')
        }
        if (baseUrl.isEmpty() || baseUrl.contains("your-cloud-run-service")) return emptyList()

        return try {
            val idToken = try {
                Firebase.auth.currentUser?.getIdToken(false)?.await()?.token.orEmpty()
            } catch (_: Exception) {
                ""
            }
            val url = URL("$baseUrl/propose-readings")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2200
                readTimeout = 2500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (idToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $idToken")
                }
            }
            val payload = JSONObject().put("name", nameInput).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            if (conn.responseCode in 200..299) {
                val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseJsonReadings(raw)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cloud Run /propose-readings fallback: ${e.message}")
            emptyList()
        }
    }

    suspend fun proposeReadings(
        nameInput: String,
        cloudRunBaseUrl: String = AppConfig.DEFAULT_CLOUD_RUN_BASE_URL
    ): List<ReadingOption> = withContext(Dispatchers.IO) {
        val cleaned = nameInput.trim()
        if (cleaned.isEmpty()) return@withContext emptyList()

        val localSuggestions = buildLocalFallback(cleaned)
        if (isFullyCoveredLocally(cleaned)) {
            return@withContext localSuggestions
        }
        geminiReadingCache[cleaned]?.let { return@withContext it }

        // 1. Try Cloud Run /propose-readings (SudachiPy + Gemini Flash on backend)
        val cloudReadings = fetchCloudRunReadings(cleaned, cloudRunBaseUrl)
        if (cloudReadings.isNotEmpty()) {
            val combined = enrichWithPhoneticIfNeeded(
                (cloudReadings + localSuggestions).distinctBy { it.fullReading.lowercase().replace(" ", "") }
            )
            geminiReadingCache[cleaned] = combined
            return@withContext combined
        }

        // 2. Fallback to Firebase Vertex AI on-device client if configured
        val isEnglishInput = cleaned.any { it in 'A'..'Z' || it in 'a'..'z' }
        try {
            withTimeout(3000L) {
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
                    1. Its English phonetic pronunciation respelling (syllables separated by hyphens, e.g. "AH-leks SMITH").
                    2. Its Japanese Katakana pronunciation as spoken over a Japanese PA system (e.g. "アレックス スミス").
                    Return JSON: {"readings": [
                      {"full_reading": "AH-leks SMITH", "surname_reading": "SMITH", "note": "Phonetic"},
                      {"full_reading": "アレックス スミス", "surname_reading": "スミス", "note": "Japanese PA"}
                    ]}
                    """.trimIndent()
                } else {
                    """
                    Propose all common Japanese Katakana readings and English phonetic respelling for the Japanese personal name "$cleaned".
                    Return JSON: {"readings": [
                      {"full_reading": "ショウジ タロウ", "surname_reading": "ショウジ", "note": "一般的"},
                      {"full_reading": "SHOH-jee TAH-roh", "surname_reading": "SHOH-jee", "note": "Phonetic"}
                    ]}
                    """.trimIndent()
                }

                val response = model.generateContent(content { text(prompt) })
                val rawJson = response.text ?: "{}"
                val parsed = parseJsonReadings(rawJson)
                if (parsed.isNotEmpty()) {
                    val combined = enrichWithPhoneticIfNeeded(
                        (parsed + localSuggestions).distinctBy { it.fullReading.lowercase().replace(" ", "") }
                    )
                    geminiReadingCache[cleaned] = combined
                    return@withTimeout combined
                }
                localSuggestions
            }
        } catch (e: Exception) {
            Log.d(TAG, "Using instant local phonetic/katakana readings: ${e.message}")
            localSuggestions
        }
    }

    private fun enrichWithPhoneticIfNeeded(options: List<ReadingOption>): List<ReadingOption> {
        if (options.isEmpty()) return options
        if (options.any { isPhoneticRespelling(it.fullReading, it.note) }) return options
        val primary = options.first()
        val fullPhon = katakanaWordToPhonetic(primary.fullReading)
        val surPhon = katakanaWordToPhonetic(primary.surnameReading)
        if (fullPhon.isNotEmpty() && surPhon.isNotEmpty()) {
            return options + ReadingOption(fullReading = fullPhon, surnameReading = surPhon, note = "Phonetic")
        }
        return options
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

        var p = clean
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("qu", "kw")
            .replace("tion", "-shun")
            .replace("sion", "-zhun")
            .replace(Regex("c([eiy])"), "s$1")

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
            if (!matched) i++
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

    /**
     * Converts a Kanji/Kana token into Katakana using whole-word dictionaries first,
     * then longest sub-word match, then per-character Kanji reading lookup.
     */
    private fun kanjiTokenToKatakana(token: String): String {
        if (token.isEmpty()) return ""
        MULTI_READING_SURNAMES[token]?.firstOrNull()?.first?.let { return it }
        COMMON_GIVEN_NAMES[token]?.firstOrNull()?.let { return it }

        val sb = StringBuilder()
        var i = 0
        while (i < token.length) {
            val ch = token[i]
            if (ch in '\u3041'..'\u3096') {
                sb.append((ch.code + 0x60).toChar())
                i++
                continue
            }
            if (ch in '\u30A0'..'\u30FF') {
                sb.append(ch)
                i++
                continue
            }
            if (ch == '々' && i > 0) {
                val prevReading = KANJI_CHAR_READINGS[token[i - 1]]
                if (prevReading != null) {
                    sb.append(prevReading)
                    i++
                    continue
                }
            }
            val charReading = KANJI_CHAR_READINGS[ch]
            if (charReading != null) {
                sb.append(charReading)
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun splitJapaneseSurnameAndGiven(normalized: String): Pair<String, String> {
        val parts = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            return parts[0] to parts.drop(1).joinToString("")
        }
        // Check longest matching surname prefix in MULTI_READING_SURNAMES
        val matchedPrefix = MULTI_READING_SURNAMES.keys
            .filter { normalized.startsWith(it) && normalized.length > it.length }
            .maxByOrNull { it.length }
        if (matchedPrefix != null) {
            return matchedPrefix to normalized.removePrefix(matchedPrefix)
        }
        // Heuristic split when user types full Kanji name without spaces (e.g. 4 chars -> 2+2, 3 chars -> 2+1)
        return when {
            normalized.length >= 4 -> normalized.substring(0, 2) to normalized.substring(2)
            normalized.length == 3 -> normalized.substring(0, 2) to normalized.substring(2)
            else -> normalized to ""
        }
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

        val (surnameKanji, givenKanji) = splitJapaneseSurnameAndGiven(normalized)

        val surnamePairs = MULTI_READING_SURNAMES[surnameKanji]
            ?: listOf(kanjiTokenToKatakana(surnameKanji) to "標準")
        val givenReadings = if (givenKanji.isEmpty()) {
            listOf("")
        } else {
            COMMON_GIVEN_NAMES[givenKanji] ?: listOf(kanjiTokenToKatakana(givenKanji))
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
        return enrichWithPhoneticIfNeeded(options)
    }
}
