package io.github.jeefyxr.diting

import android.content.Context

/** Reader settings and per-book reading positions. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var fontSp: Int
        get() = sp.getInt("font", 19)
        set(v) = sp.edit().putInt("font", v.coerceIn(12, 40)).apply()

    /** Line spacing multiplier, stored in tenths. */
    var spacingTenths: Int
        get() = sp.getInt("spacing", 15)
        set(v) = sp.edit().putInt("spacing", v.coerceIn(10, 25)).apply()

    var marginDp: Int
        get() = sp.getInt("margin", 20)
        set(v) = sp.edit().putInt("margin", v.coerceIn(0, 48)).apply()

    var theme: Int
        get() = sp.getInt("theme", 1).coerceIn(0, THEMES.size - 1)
        set(v) = sp.edit().putInt("theme", v).apply()

    var vertical: Boolean
        get() = sp.getBoolean("vertical", false)
        set(v) = sp.edit().putBoolean("vertical", v).apply()

    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", true)
        set(v) = sp.edit().putBoolean("volumeKeys", v).apply()

    /** Auto page turn: seconds for the cover line to sweep one page. */
    var autoSeconds: Int
        get() = sp.getInt("autoSeconds", 30)
        set(v) = sp.edit().putInt("autoSeconds", v.coerceIn(5, 180)).apply()

    var voice: Int
        get() = sp.getInt("voice", VOICE_SYSTEM)
        set(v) = sp.edit().putInt("voice", v).apply()

    /** Speech rate in tenths (10 = normal). */
    var rateTenths: Int
        get() = sp.getInt("rate", 10)
        set(v) = sp.edit().putInt("rate", v.coerceIn(5, 30)).apply()

    fun progress(bookId: String): Pair<Int, Int> {
        val s = sp.getString("pos:$bookId", null)?.split(':') ?: return 0 to 0
        return (s.getOrNull(0)?.toIntOrNull() ?: 0) to (s.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun saveProgress(bookId: String, chapter: Int, offset: Int) =
        sp.edit().putString("pos:$bookId", "$chapter:$offset").apply()

    fun clearProgress(bookId: String) = sp.edit().remove("pos:$bookId").apply()

    class Theme(val name: String, val background: Int, val text: Int)

    companion object {
        const val VOICE_SYSTEM = 0
        const val VOICE_FEMALE = 1
        const val VOICE_MALE = 2

        val THEMES = listOf(
            Theme("白", 0xFFFFFFFF.toInt(), 0xFF1F1F1F.toInt()),
            Theme("纸", 0xFFF4ECD8.toInt(), 0xFF3B2F1E.toInt()),
            Theme("绿", 0xFFCCE8CF.toInt(), 0xFF1F3324.toInt()),
            Theme("夜", 0xFF121212.toInt(), 0xFF9E9E9E.toInt()),
        )
    }
}
