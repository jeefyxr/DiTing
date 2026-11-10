package io.github.jeefyxr.diting.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.jeefyxr.diting.Prefs
import io.github.jeefyxr.diting.data.BookMeta
import io.github.jeefyxr.diting.data.BookStore
import io.github.jeefyxr.diting.tts.SherpaModel
import io.github.jeefyxr.diting.tts.SherpaSpeaker
import io.github.jeefyxr.diting.tts.Speaker
import io.github.jeefyxr.diting.tts.SystemSpeaker
import io.github.jeefyxr.diting.ui.IconDrawable
import io.github.jeefyxr.diting.ui.Palette
import io.github.jeefyxr.diting.ui.Ui
import java.io.File
import kotlin.concurrent.thread

class ReaderActivity : Activity(), ReaderView.Source {
    private lateinit var prefs: Prefs
    private lateinit var store: BookStore
    private lateinit var meta: BookMeta
    private lateinit var view: ReaderView
    private lateinit var root: FrameLayout
    private lateinit var ui: Ui
    private val modelDir by lazy { File(filesDir, "tts-model") }

    // Chrome: everything drawn over the page. Rebuilt whenever the theme or skin changes.
    private var overlay = NONE
    private var scrim: View? = null
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var settingsSheet: View? = null
    private var settingsScroll: ScrollView? = null
    private var tocSheet: View? = null
    private var tocList: ListView? = null
    private var progressLabel: TextView? = null
    private var cutoutTop = 0
    private var cutoutBottom = 0

    /** Few most recently used chapter texts. */
    private val texts = object : LinkedHashMap<Int, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>) = size > 4
    }

    private var speaker: Speaker? = null
    private var speaking = false
    private var speechPos = 0
    private var speechGeneration = 0

    // ---- ReaderView.Source ----------------------------------------------------------------

    override val chapterCount get() = meta.chapters.size
    override fun chapterTitle(index: Int) = meta.chapters[index].title
    override fun chapterText(index: Int): String = texts.getOrPut(index) {
        try {
            store.readChapter(meta, index)
        } catch (e: Exception) {
            "（本章读取失败）"
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = BookStore(File(filesDir, "books"))
        val loaded = intent.getStringExtra(EXTRA_BOOK)?.let(store::load)
        if (loaded == null || loaded.chapters.isEmpty()) {
            finish()
            return
        }
        meta = loaded

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        view = ReaderView(this)
        view.source = this
        view.onCenterTap = { if (view.autoRunning) stopAuto(); show(MENU) }
        view.onPageChanged = { byUser -> onPageChanged(byUser) }
        view.onEdge = { dir -> toast(if (dir > 0) "已经是最后一页" else "已经是第一页") }
        view.onAutoFinished = { stopAuto(); toast("已经是最后一页") }

        root = FrameLayout(this)
        root.addView(view, MATCH_PARENT, MATCH_PARENT)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            val bottom = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetBottom ?: 0 else 0
            if (top != cutoutTop || bottom != cutoutBottom) {
                cutoutTop = top
                cutoutBottom = bottom
                buildChrome()
            }
            insets
        }
        setContentView(root)
        hideSystemBars()

        applyStyle()
        buildChrome()
        val (chapter, offset) = prefs.progress(meta.id)
        view.open(chapter, offset)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        if (::view.isInitialized) {
            stopAuto()
            saveProgress()
        }
    }

    override fun onDestroy() {
        speechGeneration++
        speaker?.release()
        speaker = null
        super.onDestroy()
    }

    /** Back closes whatever is open over the page first. Android 13+ delivers back through a callback. */
    private val closeOverlayOnBack by lazy { android.window.OnBackInvokedCallback { show(NONE) } }
    private var backCallbackRegistered = false

    @Deprecated("Deprecated in Java")
    @Suppress("GestureBackNavigation")   // handled by closeOverlayOnBack on API 33+
    override fun onBackPressed() {
        if (overlay != NONE) show(NONE) else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isPageKey(keyCode)) {
            view.turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isPageKey(keyCode)) true else super.onKeyUp(keyCode, event)

    /** Volume keys turn pages, except while speech is playing, when they must control volume. */
    private fun isPageKey(keyCode: Int) =
        (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            prefs.volumeKeys && !speaking && overlay == NONE

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun palette() = Prefs.THEMES[prefs.theme].let { Palette(it.background, it.text) }

    private fun applyStyle() {
        val p = palette()
        window.decorView.setBackgroundColor(p.background)
        view.applyStyle(
            ReaderView.Style(
                fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, prefs.fontSp.toFloat(), resources.displayMetrics),
                lineSpacing = Prefs.LINE_SPACINGS[prefs.lineSpacing],
                paragraphSpacing = Prefs.PARAGRAPH_SPACINGS[prefs.paragraphSpacing],
                marginPx = Prefs.MARGINS_DP[prefs.margin] * resources.displayMetrics.density,
                background = p.background,
                text = p.text,
                accent = p.accent,
                turnMode = prefs.turnMode,
                serif = prefs.serif,
                indent = prefs.indent,
            )
        )
    }

    /** Re-reads the settings; lets tests change them without driving every control. */
    internal fun recreateStyleForTest() {
        applyStyle()
        buildChrome()
    }

    private fun saveProgress() = prefs.saveProgress(meta.id, view.chapter, view.offset, view.bookFraction())

    private fun onPageChanged(byUser: Boolean) {
        saveProgress()
        updateProgressLabel()
        if (byUser && speaking) restartSpeechHere()
    }

    private fun updateProgressLabel(chapter: Int = view.chapter) {
        val percent = if (chapter == view.chapter) view.bookFraction() else chapter.toFloat() / chapterCount
        progressLabel?.text = "${chapterTitle(chapter)}  ·  ${"%.1f".format(percent * 100)}%"
    }

    private fun refresh() {
        ui.refresh()
        updateProgressLabel()
        if (view.autoRunning || speaking) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- auto page turn -------------------------------------------------------------------

    private fun startAuto() {
        stopSpeech()
        show(NONE)
        view.startAuto(prefs.autoSeconds)
        refresh()
    }

    private fun stopAuto() {
        view.stopAuto()
        refresh()
    }

    // ---- speech ---------------------------------------------------------------------------

    private fun offlineVoice() = prefs.voice != Prefs.VOICE_SYSTEM && SherpaModel.isInstalled(modelDir)

    private fun startSpeech() {
        stopAuto()
        stopSpeech()
        val rate = prefs.rateTenths / 10f
        speaker = if (offlineVoice()) {
            val sid = if (prefs.voice == Prefs.VOICE_MALE) SherpaModel.AISHELL3_MALE else SherpaModel.AISHELL3_FEMALE
            SherpaSpeaker(modelDir, sid, rate)
        } else {
            SystemSpeaker(this, rate)
        }
        speaking = true
        speechPos = view.offset
        refresh()
        speakNext()
    }

    private fun stopSpeech() {
        speechGeneration++
        speaking = false
        speaker?.release()
        speaker = null
        if (::view.isInitialized) {
            view.setHighlight(-1, -1)
            refresh()
        }
    }

    /** The user moved to another page while listening: continue from the top of that page. */
    private fun restartSpeechHere() {
        speechGeneration++
        speaker?.stop()
        speechPos = view.offset
        speakNext()
    }

    private fun speakNext() {
        val generation = speechGeneration
        while (speaking) {
            val text = view.chapterText()
            if (speechPos >= text.length) {
                if (view.chapter + 1 >= chapterCount) {
                    stopSpeech()
                    toast("全书已读完")
                    return
                }
                view.open(view.chapter + 1, 0)
                speechPos = 0
                continue
            }
            val end = Sentences.end(text, speechPos)
            val sentence = text.substring(speechPos, end)
            if (!Sentences.isSpeakable(sentence)) {
                speechPos = end
                continue
            }
            while (speechPos >= view.pageEndOffset()) {
                if (!view.turn(1, animate = false, byUser = false)) break
            }
            view.setHighlight(speechPos, end)
            if (end < text.length) {
                val next = text.substring(end, Sentences.end(text, end))
                if (Sentences.isSpeakable(next)) speaker?.prefetch(next.trim())
            }
            speaker?.speak(sentence.trim()) { ok ->
                if (generation != speechGeneration) return@speak
                if (ok) {
                    speechPos = end
                    speakNext()
                } else {
                    val offline = offlineVoice()
                    stopSpeech()
                    toast(if (offline) "离线语音合成失败" else "系统语音引擎不可用或不支持中文，可改用离线语音")
                }
            }
            return
        }
    }

    private fun chooseVoice(voice: Int) {
        if (voice != Prefs.VOICE_SYSTEM && !SherpaModel.isInstalled(modelDir)) {
            promptModelImport()
            return
        }
        prefs.voice = voice
        if (speaking) startSpeech()
    }

    private fun dialogTheme() =
        if (ui.p.night) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun promptModelImport() {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("导入离线语音模型")
            .setMessage(
                "离线男声/女声使用 sherpa-onnx 的 aishell3 模型。\n\n" +
                    "1. 在浏览器打开 github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models\n" +
                    "2. 下载 vits-icefall-zh-aishell3.tar.bz2（约 32MB）\n" +
                    "3. 点“选择文件”并选中它"
            )
            .setPositiveButton("选择文件") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_MODEL)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK || uri == null) return
        stopSpeech()
        val dialog = AlertDialog.Builder(this, dialogTheme()).setMessage("正在导入模型，请稍候…").setCancelable(false).show()
        thread {
            val error = try {
                (contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取文件")).use {
                    SherpaModel.install(it, modelDir)
                }
                null
            } catch (e: Exception) {
                e.message ?: "导入失败"
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog.dismiss()
                toast(error ?: "模型已导入，可以选择女声或男声")
                refresh()
            }
        }
    }

    // ---- chrome ---------------------------------------------------------------------------

    /** Shows one of [NONE], [MENU], [SETTINGS], [TOC] over the page. */
    private fun show(next: Int, animate: Boolean = true) {
        val previous = overlay
        overlay = next
        fun set(v: View?, on: Boolean, fromTop: Boolean) {
            v ?: return
            val distance = (if (v.height > 0) v.height else dp(320)).toFloat() * if (fromTop) -1 else 1
            v.animate().cancel()
            if (on) {
                if (v.visibility != View.VISIBLE) {
                    v.visibility = View.VISIBLE
                    v.translationY = if (animate) distance else 0f
                }
                if (animate) v.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.8f)).start()
                else v.translationY = 0f
            } else if (v.visibility == View.VISIBLE) {
                if (animate) v.animate().translationY(distance).setDuration(180).withEndAction { v.visibility = View.GONE }.start()
                else v.visibility = View.GONE
            }
        }
        set(topBar, next == MENU, true)
        set(bottomBar, next == MENU, false)
        set(settingsSheet, next == SETTINGS, false)
        set(tocSheet, next == TOC, false)
        scrim?.let { s ->
            s.animate().cancel()
            val dim = if (next == SETTINGS || next == TOC) 1f else 0f
            scrimHolder?.visibility = if (next == NONE) View.GONE else View.VISIBLE
            if (animate) s.animate().alpha(dim).setDuration(200).start() else s.alpha = dim
        }
        if (next == TOC && previous != TOC) tocList?.let {
            (it.adapter as BaseAdapter).notifyDataSetChanged()
            it.setSelectionFromTop(view.chapter, dp(120))
        }
        if (Build.VERSION.SDK_INT >= 33 && (next != NONE) != backCallbackRegistered) {
            backCallbackRegistered = next != NONE
            if (backCallbackRegistered) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeOverlayOnBack)
            } else {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeOverlayOnBack)
            }
        }
        if (next != NONE) refresh()
    }

    private fun buildChrome() {
        val scrollY = settingsScroll?.scrollY ?: 0
        listOf(scrim, topBar, bottomBar, settingsSheet, tocSheet).forEach { it?.let(root::removeView) }
        ui = Ui(this, palette(), prefs.softUi)

        // A full-screen catcher: any tap outside the chrome closes it. Dimmed behind sheets.
        val catcher = FrameLayout(this)
        val dim = View(this)
        dim.setBackgroundColor(0x66000000)
        catcher.addView(dim, MATCH_PARENT, MATCH_PARENT)
        catcher.setOnClickListener { show(NONE) }
        scrim = dim
        root.addView(catcher, MATCH_PARENT, MATCH_PARENT)
        scrimHolder = catcher

        topBar = buildTopBar().also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)) }
        bottomBar = buildBottomBar().also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)) }
        settingsSheet = buildSettings().also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)) }
        val tocHeight = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        tocSheet = buildContents().also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, tocHeight, Gravity.BOTTOM)) }

        listOf(topBar, bottomBar, settingsSheet, tocSheet).forEach { it?.visibility = View.GONE }
        dim.alpha = 0f
        catcher.visibility = View.GONE
        show(overlay, animate = false)
        scrimHolder?.visibility = if (overlay == NONE) View.GONE else View.VISIBLE
        settingsScroll?.post { settingsScroll?.scrollTo(0, scrollY) }
        refresh()
    }

    private var scrimHolder: View? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.setBackgroundColor(ui.surface)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.isClickable = true
        bar.setPadding(dp(6), cutoutTop + dp(4), dp(16), dp(4))
        bar.addView(ui.icon(IconDrawable.BACK) { finish() })
        val title = ui.singleLine(ui.text(meta.title, 16.5f, medium = true))
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(bar)
        wrap.addView(ui.edgeShadow(downward = true), LinearLayout.LayoutParams(MATCH_PARENT, dp(10)))
        return wrap
    }

    private fun buildBottomBar(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(ui.surface)
        col.isClickable = true
        col.setPadding(dp(8), dp(16), dp(8), dp(8) + cutoutBottom)

        val label = ui.singleLine(ui.text("", 12.5f, ui.p.muted))
        progressLabel = label
        col.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = dp(16); marginEnd = dp(16) })

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        fun jump(text: String, delta: Int) = ui.text(text, 13.5f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = ui.pressable(null)
            setOnClickListener { openChapter(view.chapter + delta) }
            ui.refreshers.add {
                val possible = view.chapter + delta in 0 until chapterCount
                isEnabled = possible
                setTextColor(if (possible) ui.p.text else ui.p.faint)
            }
        }
        seekRow.addView(jump("上一章", -1), LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        val seek = ui.slider(maxOf(1, chapterCount - 1), { view.chapter }) { value, done ->
            val target = value.coerceIn(0, chapterCount - 1)
            if (done) openChapter(target) else updateProgressLabel(target)
        }
        seek.isEnabled = chapterCount > 1
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        seekRow.addView(jump("下一章", 1), LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        col.addView(seekRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        val tabs = LinearLayout(this)
        fun tab(kind: () -> Int, label: () -> String, active: () -> Boolean = { false }, onClick: () -> Unit) =
            tabs.addView(ui.tab(kind, label, active, onClick), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tab({ IconDrawable.LIST }, { "目录" }) { show(TOC) }
        tab({ if (ui.p.night) IconDrawable.SUN else IconDrawable.MOON }, { if (ui.p.night) "日间" else "夜间" }) {
            prefs.theme = if (ui.p.night) prefs.dayTheme.takeIf { !Prefs.THEMES[it].dark } ?: 0 else Prefs.NIGHT_THEME
            applyStyle()
            buildChrome()
        }
        tab({ if (view.autoRunning) IconDrawable.PAUSE else IconDrawable.PLAY }, { "自动" }, { view.autoRunning }) {
            if (view.autoRunning) stopAuto() else startAuto()
        }
        tab({ if (speaking) IconDrawable.PAUSE else IconDrawable.VOICE }, { if (speaking) "停止" else "朗读" }, { speaking }) {
            if (speaking) stopSpeech() else { show(NONE); startSpeech() }
        }
        tab({ IconDrawable.TEXT }, { "设置" }) { show(SETTINGS) }
        col.addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(ui.edgeShadow(downward = false), LinearLayout.LayoutParams(MATCH_PARENT, dp(12)))
        wrap.addView(col)
        return wrap
    }

    private fun openChapter(index: Int) {
        if (index !in 0 until chapterCount) return
        view.open(index, 0)
        refresh()
        if (speaking) restartSpeechHere()
    }

    private fun sheetShell(): LinearLayout {
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = ui.sheet()
        sheet.clipToOutline = true
        sheet.elevation = dp(24).toFloat()
        sheet.isClickable = true   // taps on the sheet must not fall through to the page
        return sheet
    }

    private fun buildSettings(): View {
        val sheet = sheetShell()
        val grabber = View(this)
        grabber.background = ui.shape(ui.p.outline, 100f)
        sheet.addView(grabber, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
            bottomMargin = dp(4)
        })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(4), 0, dp(20) + cutoutBottom)

        fun section(title: String) {
            val t = ui.text(title, 12f, ui.p.faint, medium = true)
            t.letterSpacing = 0.12f
            col.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18); bottomMargin = dp(4) })
        }
        fun row(label: String, control: View, heightDp: Int = 56): LinearLayout {
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.minimumHeight = dp(heightDp)
            r.addView(ui.text(label, 14f, ui.p.muted), LinearLayout.LayoutParams(dp(76) - ui.pad, WRAP_CONTENT))
            r.addView(control, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            col.addView(r)
            return r
        }
        fun sliderRow(label: String, slider: View, value: () -> String) {
            val box = LinearLayout(this)
            box.gravity = Gravity.CENTER_VERTICAL
            box.addView(slider, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.pad })
            val v = ui.text("", 13.5f, medium = true)
            v.gravity = Gravity.END
            ui.refreshers.add { v.text = value() }
            box.addView(v, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT).apply { marginEnd = dp(24) })
            row(label, box)
        }
        fun switchRow(label: String, checked: () -> Boolean, onChange: (Boolean) -> Unit) {
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.minimumHeight = dp(56)
            r.addView(ui.text(label, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(ui.toggle(checked, onChange).also {
                (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(24) - ui.pad
            })
            col.addView(r)
        }
        fun restyle() { applyStyle(); updateProgressLabel() }

        section("排版")
        row("字号", ui.fontStepper({ "${prefs.fontSp}" }) { prefs.fontSp += it; restyle() })
        row("行距", ui.chips(listOf("紧凑", "适中", "舒展", "宽松"), { prefs.lineSpacing }) { prefs.lineSpacing = it; restyle() })
        row("段距", ui.chips(listOf("无", "小", "中", "大"), { prefs.paragraphSpacing }) { prefs.paragraphSpacing = it; restyle() })
        row("边距", ui.chips(listOf("窄", "中", "宽"), { prefs.margin }) { prefs.margin = it; restyle() })
        row("字体", ui.chips(listOf("黑体", "宋体"), { if (prefs.serif) 1 else 0 }) { prefs.serif = it == 1; restyle() })
        switchRow("首行缩进", { prefs.indent }) { prefs.indent = it; restyle() }

        section("外观")
        val swatches = Prefs.THEMES.mapIndexed { i, t ->
            ui.swatch(t.name, t.background, t.text, { prefs.theme == i }) {
                if (prefs.theme != i) { prefs.theme = i; applyStyle(); buildChrome() }
            }
        }
        row("背景", ui.spread(swatches).apply { setPadding(0, 0, dp(18), 0) }, 88)
        row("界面", ui.chips(listOf("Material", "Soft"), { if (prefs.softUi) 1 else 0 }) {
            if (prefs.softUi != (it == 1)) { prefs.softUi = it == 1; buildChrome() }
        })

        section("翻页")
        row("动画", ui.chips(listOf("平移", "覆盖", "上下", "无"), { prefs.turnMode }) { prefs.turnMode = it; restyle() })
        sliderRow("自动速度", ui.slider(23, { (prefs.autoSeconds - 5) / 5 }) { value, _ ->
            prefs.autoSeconds = 5 + value * 5
            ui.refresh()
            if (view.autoRunning) view.startAuto(prefs.autoSeconds)
        }) { "${prefs.autoSeconds} 秒/页" }
        switchRow("音量键翻页", { prefs.volumeKeys }) { prefs.volumeKeys = it }

        section("朗读")
        val voices = ui.chips(listOf("系统", "女声", "男声"), { if (offlineVoice()) prefs.voice else Prefs.VOICE_SYSTEM }) { chooseVoice(it) }
        row("语音", voices)
        sliderRow("语速", ui.slider(20, { prefs.rateTenths - 5 }) { value, done ->
            prefs.rateTenths = value + 5
            ui.refresh()
            if (done && speaking) startSpeech()
        }) { "${"%.1f".format(prefs.rateTenths / 10f)}×" }
        row("离线模型", LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.button(if (SherpaModel.isInstalled(modelDir)) "重新导入" else "导入模型") { promptModelImport() })
            val state = ui.text("", 12.5f, ui.p.faint)
            ui.refreshers.add { state.text = if (SherpaModel.isInstalled(modelDir)) "已导入" else "未导入，女声/男声需要它" }
            addView(state, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4) })
        })

        val scroll = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.72f).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(col)
        settingsScroll = scroll
        sheet.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return sheet
    }

    private fun buildContents(): View {
        val sheet = sheetShell()
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(28), dp(22), dp(12), dp(12))
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.addView(ui.text("目录", 19f, medium = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val underline = View(this)
        underline.background = ui.shape(ui.p.accent, 100f)
        titleCol.addView(underline, LinearLayout.LayoutParams(dp(22), dp(3)).apply { topMargin = dp(8) })
        header.addView(titleCol)
        val count = ui.text("共 $chapterCount 章", 12.5f, ui.p.faint)
        header.addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        header.addView(ui.icon(IconDrawable.CLOSE, ui.p.muted) { show(NONE) })
        sheet.addView(header)
        sheet.addView(ui.hairline(), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1) / 2)))

        val list = ListView(this)
        list.divider = null
        list.selector = ui.pressable(null, 0f)
        list.isVerticalScrollBarEnabled = false
        list.clipToPadding = false
        list.setPadding(0, dp(8), 0, dp(16) + cutoutBottom)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = chapterCount
            override fun getItem(position: Int) = meta.chapters[position].title
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? TextView) ?: ui.singleLine(ui.text("", 15.5f)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(28), 0, dp(24), 0)
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, dp(54))
                }
                val current = position == view.chapter
                row.text = meta.chapters[position].title
                row.setTextColor(if (current) ui.p.accent else Palette.withAlpha(ui.p.text, 0.86f))
                row.typeface = if (current) Ui.MEDIUM else Typeface.DEFAULT
                return row
            }
        }
        list.setOnItemClickListener { _, _, i, _ ->
            show(NONE)
            openChapter(i)
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }
        tocList = list
        sheet.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return sheet
    }

    /** A scroll view that wraps its content up to a maximum height. */
    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }

    companion object {
        const val EXTRA_BOOK = "book"
        private const val REQUEST_MODEL = 2
        private const val NONE = 0
        private const val MENU = 1
        private const val SETTINGS = 2
        private const val TOC = 3
    }
}
