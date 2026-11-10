package io.github.jeefyxr.diting

import android.content.Context

/** Reader settings and per-book reading positions. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private fun int(key: String, default: Int, range: IntRange) = sp.getInt(key, default).coerceIn(range)
    private fun put(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    private fun put(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    var fontSp: Int
        get() = int("font", 19, 12..40)
        set(v) = put("font", v.coerceIn(12, 40))

    /** Index into [LINE_SPACINGS]. */
    var lineSpacing: Int
        get() = int("lineSpacing", 1, LINE_SPACINGS.indices)
        set(v) = put("lineSpacing", v)

    /** Index into [PARAGRAPH_SPACINGS]. */
    var paragraphSpacing: Int
        get() = int("paragraphSpacing", 2, PARAGRAPH_SPACINGS.indices)
        set(v) = put("paragraphSpacing", v)

    /** Index into [MARGINS_DP]. */
    var margin: Int
        get() = int("marginSize", 1, MARGINS_DP.indices)
        set(v) = put("marginSize", v)

    var theme: Int
        get() = int("theme", 0, THEMES.indices)
        set(v) {
            put("theme", v)
            if (!THEMES[v].dark) put("dayTheme", v)
        }

    /** The light theme to return to from night mode. */
    val dayTheme: Int get() = int("dayTheme", 0, THEMES.indices)

    var turnMode: Int
        get() = int("turnMode", TURN_COVER, 0..3)
        set(v) = put("turnMode", v)

    /** Interface skin: false = Material, true = Soft UI. */
    var softUi: Boolean
        get() = sp.getBoolean("softUi", false)
        set(v) = put("softUi", v)

    var serif: Boolean
        get() = sp.getBoolean("serif", false)
        set(v) = put("serif", v)

    var indent: Boolean
        get() = sp.getBoolean("indent", true)
        set(v) = put("indent", v)

    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", true)
        set(v) = put("volumeKeys", v)

    /** Auto page turn: seconds for the cover line to sweep one page. */
    var autoSeconds: Int
        get() = int("autoSeconds", 30, 5..120)
        set(v) = put("autoSeconds", v.coerceIn(5, 120))

    var voice: Int
        get() = int("voice", VOICE_SYSTEM, 0..2)
        set(v) = put("voice", v)

    /** Speech rate in tenths (10 = normal). */
    var rateTenths: Int
        get() = int("rate", 10, 5..25)
        set(v) = put("rate", v.coerceIn(5, 25))

    fun progress(bookId: String): Pair<Int, Int> {
        val s = sp.getString("pos:$bookId", null)?.split(':') ?: return 0 to 0
        return (s.getOrNull(0)?.toIntOrNull() ?: 0) to (s.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** [fraction] is the position within the whole book (0..1), kept for the bookshelf. */
    fun saveProgress(bookId: String, chapter: Int, offset: Int, fraction: Float) = sp.edit()
        .putString("pos:$bookId", "$chapter:$offset")
        .putFloat("pct:$bookId", fraction.coerceIn(0f, 1f))
        .putLong("read:$bookId", System.currentTimeMillis())
        .apply()

    fun fraction(bookId: String) = sp.getFloat("pct:$bookId", 0f)
    fun lastRead(bookId: String) = sp.getLong("read:$bookId", 0L)

    fun clearProgress(bookId: String) =
        sp.edit().remove("pos:$bookId").remove("pct:$bookId").remove("read:$bookId").apply()

    class Theme(val name: String, val background: Int, val text: Int, val dark: Boolean)

    companion object {
        const val VOICE_SYSTEM = 0
        const val VOICE_FEMALE = 1
        const val VOICE_MALE = 2

        const val TURN_SLIDE = 0
        const val TURN_COVER = 1
        const val TURN_VERTICAL = 2
        const val TURN_NONE = 3

        val LINE_SPACINGS = floatArrayOf(1.3f, 1.55f, 1.8f, 2.05f)
        /** Extra space after a paragraph, in multiples of the font size. */
        val PARAGRAPH_SPACINGS = floatArrayOf(0f, 0.3f, 0.6f, 1f)
        val MARGINS_DP = intArrayOf(14, 22, 32)
        const val NIGHT_THEME = 4

        val THEMES = listOf(
            Theme("纸张", 0xFFF3EAD7.toInt(), 0xFF3A3026.toInt(), false),
            Theme("素白", 0xFFFBFAF7.toInt(), 0xFF262626.toInt(), false),
            Theme("豆绿", 0xFFD9E7D3.toInt(), 0xFF243528.toInt(), false),
            Theme("墨蓝", 0xFF1A2230.toInt(), 0xFFA9B5C6.toInt(), true),
            Theme("夜间", 0xFF121212.toInt(), 0xFFA3A19C.toInt(), true),
            Theme("烛光", 0xFF1B1510.toInt(), 0xFFCBA873.toInt(), true),
        )
    }
}
