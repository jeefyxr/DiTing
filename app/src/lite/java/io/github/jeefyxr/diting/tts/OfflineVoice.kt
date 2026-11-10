package io.github.jeefyxr.diting.tts

import java.io.File
import java.io.InputStream

/** The lite build leaves the offline speech engine out (it is about 30 MB) and uses the phone's own voice. */
object OfflineVoice {
    const val AVAILABLE = false

    fun isInstalled(dir: File) = false
    fun install(input: InputStream, dir: File): Unit = throw UnsupportedOperationException()
    fun speaker(dir: File, male: Boolean, rate: Float): Speaker = throw UnsupportedOperationException()
}
