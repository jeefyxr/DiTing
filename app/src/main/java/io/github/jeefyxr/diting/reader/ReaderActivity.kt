package io.github.jeefyxr.diting.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import java.io.File
import kotlin.concurrent.thread

class ReaderActivity : Activity(), ReaderView.Source {
    private lateinit var prefs: Prefs
    private lateinit var store: BookStore
    private lateinit var meta: BookMeta
    private lateinit var view: ReaderView
    private lateinit var topBar: View
    private lateinit var panel: View
    private lateinit var autoButton: Button
    private lateinit var speakButton: Button
    private val refreshers = ArrayList<() -> Unit>()
    private val modelDir by lazy { File(filesDir, "tts-model") }

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
        view.onCenterTap = { if (view.autoRunning) stopAuto(); toggleMenu() }
        view.onPageChanged = { byUser -> onPageChanged(byUser) }
        view.onEdge = { dir -> toast(if (dir > 0) "已经是最后一页" else "已经是第一页") }
        view.onAutoFinished = { stopAuto(); toast("已经是最后一页") }

        val root = FrameLayout(this)
        root.addView(view, MATCH_PARENT, MATCH_PARENT)
        topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        panel = buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        setMenuVisible(false)
        hideSystemBars()

        applyStyle()
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
            prefs.saveProgress(meta.id, view.chapter, view.offset)
        }
    }

    override fun onDestroy() {
        speechGeneration++
        speaker?.release()
        speaker = null
        super.onDestroy()
    }

    /** Back closes the menu first. Android 13+ delivers back through a callback instead. */
    private val closeMenuOnBack by lazy { android.window.OnBackInvokedCallback { setMenuVisible(false) } }
    private var backCallbackRegistered = false

    @Deprecated("Deprecated in Java")
    @Suppress("GestureBackNavigation")   // handled by closeMenuOnBack on API 33+
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) setMenuVisible(false) else super.onBackPressed()
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
            prefs.volumeKeys && !speaking && panel.visibility != View.VISIBLE

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

    private fun applyStyle() {
        val theme = Prefs.THEMES[prefs.theme]
        view.applyStyle(
            fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, prefs.fontSp.toFloat(), resources.displayMetrics),
            spacingMult = prefs.spacingTenths / 10f,
            marginPx = dp(prefs.marginDp).toFloat(),
            backgroundColor = theme.background,
            textColor = theme.text,
            verticalTurn = prefs.vertical,
        )
    }

    /** Re-reads the settings; lets tests change them without driving every menu control. */
    internal fun recreateStyleForTest() = applyStyle()

    private fun onPageChanged(byUser: Boolean) {
        prefs.saveProgress(meta.id, view.chapter, view.offset)
        if (byUser && speaking) restartSpeechHere()
    }

    private fun keepScreenOn() {
        if (view.autoRunning || speaking) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- auto page turn -------------------------------------------------------------------

    private fun startAuto() {
        stopSpeech()
        view.startAuto(prefs.autoSeconds)
        setMenuVisible(false)
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
        setMenuVisible(false)
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
        refresh()
        if (speaking) startSpeech()
    }

    private fun promptModelImport() {
        AlertDialog.Builder(this)
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
        val dialog = AlertDialog.Builder(this).setMessage("正在导入模型，请稍候…").setCancelable(false).show()
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

    // ---- menu -----------------------------------------------------------------------------

    private fun toggleMenu() = setMenuVisible(panel.visibility != View.VISIBLE)

    private fun setMenuVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v
        panel.visibility = v
        if (Build.VERSION.SDK_INT >= 33 && visible != backCallbackRegistered) {
            backCallbackRegistered = visible
            val dispatcher = onBackInvokedDispatcher
            if (visible) dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeMenuOnBack)
            else dispatcher.unregisterOnBackInvokedCallback(closeMenuOnBack)
        }
        if (visible) refresh()
    }

    private fun refresh() {
        refreshers.forEach { it() }
        keepScreenOn()
    }

    private fun showContents() {
        val titles = meta.chapters.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setSingleChoiceItems(titles, view.chapter) { d, i ->
                d.dismiss()
                setMenuVisible(false)
                view.open(i, 0)
                if (speaking) restartSpeechHere()
            }
            .show()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.setBackgroundColor(PANEL_COLOR)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(button("返回") { finish() })
        val title = label(meta.title)
        title.setSingleLine()
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(button("目录") { showContents() })
        bar.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(8), dp(4) + cutoutTop(insets), dp(8), dp(4))
            insets
        }
        return bar
    }

    private fun cutoutTop(insets: WindowInsets) = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
    private fun cutoutBottom(insets: WindowInsets) = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetBottom ?: 0 else 0

    private fun buildPanel(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(12))

        col.addView(stepper("字号", { "${prefs.fontSp}" }) { prefs.fontSp += it; applyStyle() })
        col.addView(stepper("行距", { "%.1f".format(prefs.spacingTenths / 10f) }) { prefs.spacingTenths += it; applyStyle() })
        col.addView(stepper("边距", { "${prefs.marginDp}" }) { prefs.marginDp += it * 4; applyStyle() })

        val themes = row("配色")
        Prefs.THEMES.forEachIndexed { i, t ->
            val swatch = TextView(this)
            swatch.text = t.name
            swatch.gravity = Gravity.CENTER
            swatch.setTextColor(t.text)
            val shape = GradientDrawable()
            shape.cornerRadius = dp(6).toFloat()
            shape.setColor(t.background)
            swatch.setOnClickListener { prefs.theme = i; applyStyle(); refresh() }
            refreshers.add {
                shape.setStroke(dp(2), if (prefs.theme == i) ACCENT else Color.TRANSPARENT)
                swatch.background = shape
            }
            themes.addView(swatch, LinearLayout.LayoutParams(dp(52), dp(36)).apply { marginStart = dp(8) })
        }
        col.addView(themes)

        val turn = row("翻页")
        turn.addView(choice("左右", { !prefs.vertical }) { prefs.vertical = false; applyStyle() })
        turn.addView(choice("上下", { prefs.vertical }) { prefs.vertical = true; applyStyle() })
        val volume = CheckBox(this)
        volume.text = "音量键"
        volume.setTextColor(Color.WHITE)
        volume.isChecked = prefs.volumeKeys
        volume.setOnCheckedChangeListener { _, checked -> prefs.volumeKeys = checked }
        turn.addView(volume)
        col.addView(turn)

        val auto = stepper("自动翻页", { "${prefs.autoSeconds}秒/页" }) {
            prefs.autoSeconds += it * 5
            if (view.autoRunning) view.startAuto(prefs.autoSeconds)
        }
        autoButton = button("开始") { if (view.autoRunning) stopAuto() else startAuto() }
        refreshers.add { autoButton.text = if (view.autoRunning) "停止" else "开始" }
        auto.addView(autoButton, 1)
        col.addView(auto)

        val speech = stepper("朗读", { "语速 %.1f".format(prefs.rateTenths / 10f) }) {
            prefs.rateTenths += it
            if (speaking) startSpeech()
        }
        speakButton = button("开始") { if (speaking) stopSpeech() else startSpeech() }
        refreshers.add { speakButton.text = if (speaking) "停止" else "开始" }
        speech.addView(speakButton, 1)
        col.addView(speech)

        val voices = row("语音")
        voices.addView(choice("系统", { !offlineVoice() }) { chooseVoice(Prefs.VOICE_SYSTEM) })
        voices.addView(choice("女声", { offlineVoice() && prefs.voice == Prefs.VOICE_FEMALE }) { chooseVoice(Prefs.VOICE_FEMALE) })
        voices.addView(choice("男声", { offlineVoice() && prefs.voice == Prefs.VOICE_MALE }) { chooseVoice(Prefs.VOICE_MALE) })
        voices.addView(button("导入模型") { promptModelImport() })
        col.addView(voices)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(PANEL_COLOR)
        scroll.addView(col)
        scroll.isClickable = true   // taps on the panel must not reach the page below
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, 0, 0, cutoutBottom(insets))
            insets
        }
        return scroll
    }

    private fun row(title: String): LinearLayout {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(48)
        r.addView(label(title), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return r
    }

    private fun stepper(title: String, value: () -> String, change: (Int) -> Unit): LinearLayout {
        val r = row(title)
        val text = label("")
        text.gravity = Gravity.CENTER
        text.minWidth = dp(84)
        refreshers.add { text.text = value() }
        r.addView(button("－") { change(-1); text.text = value() })
        r.addView(text)
        r.addView(button("＋") { change(1); text.text = value() })
        return r
    }

    private fun choice(text: String, selected: () -> Boolean, onClick: () -> Unit): Button {
        val b = button(text) { onClick(); refresh() }
        refreshers.add { b.setTextColor(if (selected()) ACCENT else Color.WHITE) }
        return b
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minWidth = dp(48)
        minimumWidth = dp(48)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_BOOK = "book"
        private const val REQUEST_MODEL = 2
        private const val PANEL_COLOR = 0xFF222222.toInt()
        private const val ACCENT = 0xFF4FC3F7.toInt()
    }
}
