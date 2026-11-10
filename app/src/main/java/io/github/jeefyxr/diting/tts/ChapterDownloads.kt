package io.github.jeefyxr.diting.tts

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads of whole chapters for offline listening. They belong to the process, not to a screen,
 * so one keeps running when the reader is closed; if the app is killed it can simply be started
 * again, because sentences already stored are skipped and the server recognises repeated requests.
 */
object ChapterDownloads {
    class Job(val total: Int) {
        val done = AtomicInteger(0)
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        @Volatile var finished = false
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    /** Called (on a worker thread) whenever any job makes progress or ends. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun id(book: String, chapter: Int) = "$book/$chapter"

    /** The running or failed download of this chapter, if any. */
    fun job(book: String, chapter: Int): Job? = jobs[id(book, chapter)]

    fun cancel(book: String, chapter: Int) {
        jobs.remove(id(book, chapter))?.cancelled = true
        onChange?.invoke()
    }

    /**
     * Stores every sentence of a chapter in [library]. [fetch] produces one sentence's clip (from a
     * cache or the network) and must give up with InterruptedException once its argument says so.
     */
    fun start(
        library: SpeechLibrary, book: String, chapter: Int, voice: String, sentences: List<String>,
        keyOf: (String) -> String, fetch: (text: String, cancelled: () -> Boolean) -> AudioCache.Clip,
    ) {
        val key = id(book, chapter)
        if (jobs[key]?.let { !it.finished } == true) return
        val job = Job(sentences.size)
        jobs[key] = job
        val next = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(WORKERS)
        val running = AtomicInteger(WORKERS)
        repeat(WORKERS) {
            pool.execute {
                try {
                    while (!job.cancelled && job.error == null) {
                        val i = next.getAndIncrement()
                        if (i >= sentences.size) break
                        val text = sentences[i]
                        val k = keyOf(text)
                        if (!library.has(book, chapter, k)) {
                            library.put(book, chapter, k, fetch(text) { job.cancelled || job.error != null })
                        }
                        job.done.incrementAndGet()
                        onChange?.invoke()
                    }
                } catch (e: InterruptedException) {
                    // cancelled, or another worker failed
                } catch (e: Throwable) {
                    job.error = e.message ?: e.javaClass.simpleName
                } finally {
                    if (running.decrementAndGet() == 0) {
                        if (!job.cancelled && job.error == null && job.done.get() >= sentences.size) {
                            library.markComplete(book, chapter, voice)
                            jobs.remove(key, job)
                        }
                        job.finished = true
                        pool.shutdown()
                        onChange?.invoke()
                    }
                }
            }
        }
    }

    /** The server works through its queue one job at a time; a few requests keep that queue fed. */
    private const val WORKERS = 3
}
