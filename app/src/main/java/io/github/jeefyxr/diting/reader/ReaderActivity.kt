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
import io.github.jeefyxr.diting.tts.AudioCache
import io.github.jeefyxr.diting.tts.ChapterDownloads
import io.github.jeefyxr.diting.tts.SpeechLibrary
import io.github.jeefyxr.diting.tts.OfflineVoice
import io.github.jeefyxr.diting.tts.OnlineVoice
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
    private val voices by lazy { OfflineVoice(filesDir) }
    private val audioCache by lazy { AudioCache(File(cacheDir, "speech"), prefs.cacheMb * 1048576L) }
    private val library by lazy { SpeechLibrary(File(filesDir, "speech-library")) }
    private var usingFallback = false

    // Chrome: everything drawn over the page. Rebuilt whenever the theme or skin changes.
    private var overlay = NONE
    private var scrim: View? = null
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var settingsSheet: View? = null
    private var settingsScroll: ScrollView? = null
    private var tocSheet: View? = null
    private var tocList: ListView? = null
    private var listenSheet: View? = null
    private var listenStatus: TextView? = null
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
        view.onParagraphDoubleTap = { offset -> startSpeech(at = offset) }

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

    override fun onResume() {
        super.onResume()
        // Download progress arrives on worker threads; coalesce it into one refresh on the main thread.
        ChapterDownloads.onChange = {
            if (refreshPosted.compareAndSet(false, true)) {
                if (::root.isInitialized) {
                    root.postDelayed({ refreshPosted.set(false); if (overlay == LISTEN && !isDestroyed) ui.refresh() }, 300)
                } else {
                    refreshPosted.set(false)
                }
            }
        }
    }

    private val refreshPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onPause() {
        super.onPause()
        ChapterDownloads.onChange = null
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
                tapForwardOnly = prefs.tapForwardOnly,
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
        if (overlay == LISTEN) ui.refresh()   // the download row is about the chapter being shown
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

    private fun offlineVoice() = (prefs.voice == Prefs.VOICE_FEMALE || prefs.voice == Prefs.VOICE_MALE) && voices.isInstalled()
    private fun onlineVoice() = prefs.voice == Prefs.VOICE_ONLINE && prefs.onlineToken.isNotEmpty() && prefs.onlineVoice.isNotEmpty()

    /** The voice actually in use; a choice whose prerequisites are missing falls back to the system voice. */
    private fun activeVoice() = if (offlineVoice() || onlineVoice()) prefs.voice else Prefs.VOICE_SYSTEM

    /**
     * Starts reading aloud: from [at] if given; else (with [resume]) from where listening last
     * stopped, provided that spot is still on the page being shown; else from the top of the page.
     */
    private fun startSpeech(at: Int? = null, resume: Boolean = false) {
        stopAuto()
        stopSpeech()
        val rate = prefs.rateTenths / 10f
        usingFallback = false
        speaker = if (onlineVoice()) {
            // Downloaded chapters are looked up first, so they play with no network at all.
            OnlineVoice(prefs.onlineUrl, prefs.onlineToken).speaker(prefs.onlineVoice, rate, audioCache) { key ->
                library.get(meta.id, view.chapter, key) ?: library.get(meta.id, view.chapter + 1, key)
            }
        } else {
            localSpeaker(rate)
        }
        speaking = true
        speechPos = at ?: (if (resume) resumePoint() else null) ?: view.offset
        refresh()
        speakNext()
    }

    /** The voice that needs no network: the offline one when it is installed, else the phone's own. */
    private fun localSpeaker(rate: Float): Speaker = when {
        voices.isInstalled() && (prefs.voice != Prefs.VOICE_SYSTEM) ->
            voices.speaker(prefs.voice == Prefs.VOICE_MALE, rate, audioCache)
        else -> SystemSpeaker(this, rate)
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

    /** Jumps one sentence forward or back while listening. */
    private fun skipSentence(direction: Int) {
        if (!speaking) return
        val text = view.chapterText()
        speechGeneration++
        speaker?.stop()
        speechPos = if (direction > 0) Sentences.end(text, speechPos.coerceAtMost(text.length)) else Sentences.previousStart(text, speechPos)
        speakNext()
    }

    /**
     * Where listening last stopped, if that is on the page being shown. Listening turns the pages
     * itself, so when the reader has since turned to another page by hand this is null and
     * listening starts from the page in front of them instead.
     */
    private fun resumePoint(): Int? =
        Sentences.resumeOffset(prefs.listenPosition(meta.id), view.chapter, view.offset, view.pageEndOffset())

    private fun updateListenStatus() {
        val label = listenStatus ?: return
        label.text = when {
            speaking -> {
                val text = view.chapterText()
                val start = speechPos.coerceIn(0, text.length)
                text.substring(start, Sentences.end(text, start).coerceAtMost(text.length)).trim()
            }
            resumePoint() != null -> "将从上次听到的位置继续"
            else -> "将从当前页开始"
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
            if (speechPos < view.offset) view.open(view.chapter, speechPos)
            while (speechPos >= view.pageEndOffset()) {
                if (!view.turn(1, animate = false, byUser = false)) break
            }
            view.setHighlight(speechPos, end)
            prefs.saveListenPosition(meta.id, view.chapter, speechPos)
            updateListenStatus()
            // Synthesize a few sentences ahead so a slow voice does not stall between sentences.
            var ahead = end
            var queued = 0
            while (queued < (speaker?.lookAhead ?: 0) && ahead < text.length) {
                val aheadEnd = Sentences.end(text, ahead)
                val next = text.substring(ahead, aheadEnd)
                if (Sentences.isSpeakable(next)) { speaker?.prefetch(next.trim()); queued++ }
                ahead = aheadEnd
            }
            // A longer breath at the end of a paragraph than between its sentences.
            val pause = if (sentence.endsWith('\n') || end >= text.length) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS
            speaker?.speak(sentence.trim(), pause) { ok ->
                if (generation != speechGeneration) return@speak
                if (ok) {
                    speechPos = end
                    speakNext()
                } else {
                    val reason = speaker?.lastError
                    if (activeVoice() == Prefs.VOICE_ONLINE && !usingFallback) {
                        // The online voice is unreachable: carry on from this sentence with a local one.
                        usingFallback = true
                        speaker?.release()
                        speaker = localSpeaker(prefs.rateTenths / 10f)
                        Toast.makeText(this, "在线语音不可用（${reason ?: "未知原因"}），已改用本机语音", Toast.LENGTH_LONG).show()
                        speakNext()
                        return@speak
                    }
                    val fallback = when (activeVoice()) {
                        Prefs.VOICE_SYSTEM -> "系统语音引擎不可用或不支持中文，可改用其他语音"
                        Prefs.VOICE_ONLINE -> "在线语音合成失败"
                        else -> "离线语音合成失败"
                    }
                    stopSpeech()
                    Toast.makeText(this, reason ?: fallback, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
    }

    private fun chooseVoice(voice: Int) {
        if ((voice == Prefs.VOICE_FEMALE || voice == Prefs.VOICE_MALE) && !voices.isInstalled()) {
            promptOfflineSetup()
            return
        }
        if (voice == Prefs.VOICE_ONLINE && (prefs.onlineToken.isEmpty() || prefs.onlineVoice.isEmpty())) {
            promptOnlineSetup()
            return
        }
        prefs.voice = voice
        if (speaking) startSpeech(at = speechPos)
    }

    // ---- chapters kept for offline listening ---------------------------------------------

    private fun onlineKey() = "${prefs.onlineUrl}|${prefs.onlineVoice}"

    private fun chapterDownloaded(chapter: Int) = library.isComplete(meta.id, chapter, onlineKey())

    private fun downloadChapter(chapter: Int) {
        if (prefs.onlineToken.isEmpty() || prefs.onlineVoice.isEmpty()) {
            promptOnlineSetup()
            return
        }
        val service = OnlineVoice(prefs.onlineUrl, prefs.onlineToken)
        val voice = prefs.onlineVoice
        val sentences = Sentences.speakable(chapterTitle(chapter) + "\n" + chapterText(chapter))
        ChapterDownloads.start(
            library, meta.id, chapter, onlineKey(), sentences,
            keyOf = { text -> service.keyOf(voice, text) },
            // What was heard already is taken from the temporary cache instead of asking again.
            fetch = { text, cancelled -> audioCache.get(service.keyOf(voice, text)) ?: service.fetch(voice, text, cancelled) },
        )
        ui.refresh()
    }

    /** Asks for the online service's address and token, then lets the user pick one of its voices. */
    private fun promptOnlineSetup() {
        val pad = dp(24)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, dp(8), pad, 0)
        fun field(hint: String, value: String, secret: Boolean) = android.widget.EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
            box.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val url = field("服务地址", prefs.onlineUrl, false)
        val token = field("访问令牌", prefs.onlineToken, true)
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("在线语音")
            .setMessage("使用自建服务上的 CosyVoice 朗读。朗读时需要联网；合成过的句子会进入配音缓存，可以离线重听。")
            .setView(box)
            .setPositiveButton("选择音色") { _, _ ->
                prefs.onlineUrl = url.text.toString().ifBlank { Prefs.DEFAULT_ONLINE_URL }
                prefs.onlineToken = token.text.toString()
                pickOnlineVoice()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickOnlineVoice() {
        if (prefs.onlineToken.isEmpty()) {
            toast("需要先填写访问令牌")
            return
        }
        val wait = AlertDialog.Builder(this, dialogTheme()).setMessage("正在读取音色列表…").setCancelable(false).show()
        val service = OnlineVoice(prefs.onlineUrl, prefs.onlineToken)
        thread {
            val result = try { Result.success(service.voices()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wait.dismiss()
                val list = result.getOrNull()
                if (list.isNullOrEmpty()) {
                    AlertDialog.Builder(this, dialogTheme()).setTitle("读取失败")
                        .setMessage(result.exceptionOrNull()?.message ?: "服务器上没有可用的音色")
                        .setPositiveButton("重新填写") { _, _ -> promptOnlineSetup() }
                        .setNegativeButton("关闭", null).show()
                    return@runOnUiThread
                }
                val current = list.indexOfFirst { it.id == prefs.onlineVoice }
                AlertDialog.Builder(this, dialogTheme())
                    .setTitle("选择音色")
                    .setSingleChoiceItems(list.map { it.name }.toTypedArray(), current) { dialog, i ->
                        dialog.dismiss()
                        prefs.onlineVoice = list[i].id
                        prefs.onlineVoiceName = list[i].name
                        prefs.voice = Prefs.VOICE_ONLINE
                        ui.refresh()
                        if (speaking) startSpeech(at = speechPos)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun dialogTheme() =
        if (ui.p.night) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    /** Offers the two ways of getting the offline engine and voice model onto the phone. */
    private fun promptOfflineSetup() {
        val missing = listOfNotNull(
            "语音引擎（约 12MB）".takeIf { !voices.engineInstalled() },
            "语音模型（约 113MB）".takeIf { !voices.modelInstalled() },
        )
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("离线语音")
            .setMessage(
                if (missing.isEmpty()) "离线语音已经装好。需要的话可以重新下载或重新导入。"
                else "女声、男声需要先装好：${missing.joinToString("、")}。\n\n" +
                    "自动下载会从 GitHub 获取，只在这一步联网。网络不通时可以改用手动导入。"
            )
            .setPositiveButton("自动下载") { _, _ -> downloadOffline(force = missing.isEmpty()) }
            .setNeutralButton("手动导入") { _, _ -> promptManualImport() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptManualImport() {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("手动导入")
            .setMessage(
                "用浏览器从 github.com/k2-fsa/sherpa-onnx/releases 下载这两个文件，然后在下一步一起选中：\n\n" +
                    "引擎：v${OfflineVoice.ENGINE_VERSION} 版本里的 ${OfflineVoice.ENGINE_FILE}\n\n" +
                    "模型：tts-models 版本里的 ${OfflineVoice.MODEL_FILE}\n\n" +
                    "已经装好的那一个不用重复导入。"
            )
            .setPositiveButton("选择文件") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_MODEL)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Runs [work] off the main thread behind a progress dialog; [work] returns an error or null. */
    private fun offlineJob(title: String, onCancel: (() -> Unit)?, work: (status: (String) -> Unit) -> String?) {
        stopSpeech()
        val builder = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setMessage("正在准备…").setCancelable(false)
        if (onCancel != null) builder.setNegativeButton("取消") { _, _ -> onCancel() }
        val dialog = builder.show()
        var last = 0L
        val status = { text: String ->
            val now = android.os.SystemClock.uptimeMillis()
            if (now - last > 200) {
                last = now
                runOnUiThread { if (dialog.isShowing) dialog.setMessage(text) }
            }
        }
        thread {
            val error = try {
                work(status)
            } catch (e: Exception) {
                e.message ?: "操作失败"
            } catch (e: Throwable) {
                "操作失败：${e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (dialog.isShowing) dialog.dismiss()
                if (error == null) {
                    toast(if (voices.isInstalled()) "离线语音已就绪，可以选择女声或男声" else "已导入，还缺${if (voices.engineInstalled()) "语音模型" else "语音引擎"}")
                } else if (error != "已取消") {
                    AlertDialog.Builder(this, dialogTheme()).setTitle("没有完成").setMessage(error)
                        .setPositiveButton("手动导入") { _, _ -> promptManualImport() }
                        .setNegativeButton("关闭", null).show()
                }
                buildChrome()
            }
        }
    }

    private fun downloadOffline(force: Boolean) {
        val transfer = OfflineVoice.Transfer()
        fun mb(bytes: Long) = "%.1f".format(bytes / 1048576f)
        offlineJob("下载离线语音", { transfer.cancel() }) { status ->
            if (force || !voices.engineInstalled()) {
                voices.downloadEngine(transfer) { bytes, total -> status("语音引擎  ${mb(minOf(bytes, total))} / ${mb(total)} MB") }
            }
            if (!transfer.cancelled && (force || !voices.modelInstalled())) {
                voices.downloadModel(transfer) { bytes, total ->
                    status(if (total > 0) "语音模型  ${mb(bytes)} / ${mb(total)} MB" else "语音模型  ${mb(bytes)} MB")
                }
            }
            if (transfer.cancelled) "已取消" else null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        if (uris.isEmpty()) return
        offlineJob("导入离线语音", null) { status ->
            val errors = ArrayList<String>()
            uris.forEachIndexed { i, uri ->
                status("正在导入第 ${i + 1} 个文件，共 ${uris.size} 个…")
                try {
                    (contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取文件")).use {
                        voices.importFile(it, File(cacheDir, "offline-import.tmp"))
                    }
                } catch (e: Exception) {
                    errors.add(e.message ?: "导入失败")
                }
            }
            errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
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
        set(listenSheet, next == LISTEN, false)
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
        listOf(scrimHolder, topBar, bottomBar, settingsSheet, tocSheet, listenSheet).forEach { it?.let(root::removeView) }
        ui = Ui(this, palette())

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

        listenSheet = buildListen().also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)) }

        listOf(topBar, bottomBar, settingsSheet, tocSheet, listenSheet).forEach { it?.visibility = View.GONE }
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
        tab({ IconDrawable.TEXT }, { "设置" }) { show(SETTINGS) }
        tab({ IconDrawable.VOICE }, { "听" }, { speaking }) { show(LISTEN) }
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

        section("翻页")
        row("动画", ui.chips(listOf("平移", "覆盖", "上下", "无"), { prefs.turnMode }) { prefs.turnMode = it; restyle() })
        sliderRow("自动速度", ui.slider(23, { (prefs.autoSeconds - 5) / 5 }) { value, _ ->
            prefs.autoSeconds = 5 + value * 5
            ui.refresh()
            if (view.autoRunning) view.startAuto(prefs.autoSeconds)
        }) { "${prefs.autoSeconds} 秒/页" }
        row("点击", ui.chips(listOf("左右翻页", "全屏向后"), { if (prefs.tapForwardOnly) 1 else 0 }) { prefs.tapForwardOnly = it == 1; restyle() })
        switchRow("音量键翻页", { prefs.volumeKeys }) { prefs.volumeKeys = it }

        val scroll = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.72f).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(col)
        settingsScroll = scroll
        sheet.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return sheet
    }

    /** The listening panel: transport controls on top, voice settings below. */
    private fun buildListen(): View {
        val sheet = sheetShell()
        val grabber = View(this)
        grabber.background = ui.shape(ui.p.outline, 100f)
        sheet.addView(grabber, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(24), dp(18), dp(24), 0)
        head.addView(ui.text("听书", 19f, medium = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val status = ui.text("", 13.5f, ui.p.muted)
        status.maxLines = 2
        status.minLines = 2
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        status.setLineSpacing(0f, 1.35f)
        listenStatus = status
        ui.refreshers.add { updateListenStatus() }
        head.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        sheet.addView(head)

        val transport = LinearLayout(this)
        transport.gravity = Gravity.CENTER
        fun skip(kind: Int, direction: Int) = ui.icon(kind) { skipSentence(direction) }.also { button ->
            ui.refreshers.add { button.alpha = if (speaking) 1f else 0.3f }
        }
        transport.addView(skip(IconDrawable.SKIP_PREV, -1))
        transport.addView(
            ui.playButton({ if (speaking) IconDrawable.PAUSE else IconDrawable.PLAY }) {
                if (speaking) stopSpeech() else startSpeech(resume = true)
            },
            LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginStart = dp(28); marginEnd = dp(28) },
        )
        transport.addView(skip(IconDrawable.SKIP_NEXT, 1))
        sheet.addView(transport, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        val fromHere = ui.button("从当前页开始听") { startSpeech() }
        (fromHere.layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; marginEnd = 0; topMargin = dp(12) - ui.pad }
        sheet.addView(fromHere)
        sheet.addView(
            ui.text("阅读时双击任意一段，也可以从那里开始听", 12f, ui.p.faint).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) - ui.pad },
        )
        sheet.addView(ui.hairline(), LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1) / 2)).apply { topMargin = dp(18) })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(6), 0, dp(16) + cutoutBottom)
        fun row(label: String, control: View) {
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.minimumHeight = dp(56)
            r.addView(ui.text(label, 14f, ui.p.muted), LinearLayout.LayoutParams(dp(76) - ui.pad, WRAP_CONTENT))
            r.addView(control, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            col.addView(r)
        }
        val offlineSupported = OfflineVoice.supported(Build.SUPPORTED_ABIS)
        // Which voices are offered depends on the device; map chip positions to voice constants.
        val choices = listOf(Prefs.VOICE_SYSTEM to "系统") +
            (if (offlineSupported) listOf(Prefs.VOICE_FEMALE to "女声", Prefs.VOICE_MALE to "男声") else emptyList()) +
            listOf(Prefs.VOICE_ONLINE to "在线")
        row("语音", ui.chips(choices.map { it.second }, { choices.indexOfFirst { it.first == activeVoice() } }) { chooseVoice(choices[it].first) })
        row("在线语音", LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.button("设置") { promptOnlineSetup() })
            val state = ui.singleLine(ui.text("", 12.5f, ui.p.faint))
            ui.refreshers.add {
                state.text = when {
                    prefs.onlineToken.isEmpty() -> "需要服务地址和访问令牌"
                    prefs.onlineVoice.isEmpty() -> "还没有选择音色"
                    else -> prefs.onlineVoiceName.ifEmpty { prefs.onlineVoice }
                }
            }
            addView(state, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(24) })
        })
        val rate = LinearLayout(this)
        rate.gravity = Gravity.CENTER_VERTICAL
        rate.addView(ui.slider(20, { prefs.rateTenths - 5 }) { value, done ->
            prefs.rateTenths = value + 5
            ui.refresh()
            if (done && speaking) startSpeech(at = speechPos)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.pad })
        val rateValue = ui.text("", 13.5f, medium = true)
        rateValue.gravity = Gravity.END
        ui.refreshers.add { rateValue.text = "${"%.1f".format(prefs.rateTenths / 10f)}×" }
        rate.addView(rateValue, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT).apply { marginEnd = dp(24) })
        row("语速", rate)
        if (offlineSupported) row("离线语音", LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.button(if (voices.isInstalled()) "管理" else "获取") { promptOfflineSetup() })
            val state = ui.text("", 12.5f, ui.p.faint)
            ui.refreshers.add {
                state.text = when {
                    voices.isInstalled() -> "已就绪"
                    voices.engineInstalled() -> "还缺语音模型"
                    voices.modelInstalled() -> "还缺语音引擎"
                    else -> "女声、男声需要先下载或导入"
                }
            }
            addView(state, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4) })
        })
        val downloadButton = ui.button("") {
            val chapter = view.chapter
            val job = ChapterDownloads.job(meta.id, chapter)
            when {
                job != null && !job.finished -> ChapterDownloads.cancel(meta.id, chapter)
                chapterDownloaded(chapter) -> { library.delete(meta.id, chapter); ui.refresh() }
                else -> { ChapterDownloads.cancel(meta.id, chapter); downloadChapter(chapter) }
            }
        }
        row("本章离线", LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(downloadButton)
            val state = ui.singleLine(ui.text("", 12.5f, ui.p.faint))
            ui.refreshers.add {
                val chapter = view.chapter
                val job = ChapterDownloads.job(meta.id, chapter)
                downloadButton.text = when {
                    job != null && !job.finished -> "取消"
                    chapterDownloaded(chapter) -> "删除"
                    job?.error != null -> "重试"
                    else -> "下载本章"
                }
                state.text = when {
                    job != null && !job.finished -> "正在下载 ${job.done.get()} / ${job.total} 句"
                    chapterDownloaded(chapter) -> "已下载，可离线听"
                    job?.error != null -> "中断：${job.error}"
                    else -> "用在线语音合成整章并保存"
                }
            }
            addView(state, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(24) })
        })
        row("已下载", LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.button("清空本书") {
                AlertDialog.Builder(this@ReaderActivity, dialogTheme())
                    .setMessage("删除这本书所有已下载的语音？")
                    .setPositiveButton("删除") { _, _ -> library.deleteBook(meta.id); ui.refresh() }
                    .setNegativeButton("取消", null).show()
            })
            val used = ui.text("", 12.5f, ui.p.faint)
            ui.refreshers.add {
                val chapters = library.chapters(meta.id)
                used.text = "${chapters.size} 章 · ${"%.1f".format(chapters.values.sum() / 1048576f)} MB"
            }
            addView(used, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4) })
        })
        run {
            row("临时缓存", ui.chips(Prefs.CACHE_SIZES_MB.map { "${it}M" }, { Prefs.CACHE_SIZES_MB.indexOf(prefs.cacheMb) }) {
                prefs.cacheMb = Prefs.CACHE_SIZES_MB[it]
                audioCache.limitBytes = prefs.cacheMb * 1048576L
            })
            row("缓存占用", LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.button("清空") { audioCache.clear(); ui.refresh() })
                val used = ui.text("", 12.5f, ui.p.faint)
                ui.refreshers.add { used.text = "${"%.1f".format(audioCache.size() / 1048576f)} MB · ${audioCache.count()} 句" }
                addView(used, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4) })
            })
        }
        // The panel has grown past what fits on small screens, so its lower half scrolls.
        val scroll = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.42f).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(col)
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
        private const val LISTEN = 4
        private const val SENTENCE_PAUSE_MS = 180
        private const val PARAGRAPH_PAUSE_MS = 480
    }
}
