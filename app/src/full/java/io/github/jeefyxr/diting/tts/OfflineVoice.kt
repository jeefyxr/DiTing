package io.github.jeefyxr.diting.tts

import java.io.File
import java.io.InputStream

/** Offline voices for the full build: sherpa-onnx with an imported model. */
object OfflineVoice {
    const val AVAILABLE = true

    fun isInstalled(dir: File) = SherpaModel.isInstalled(dir)
    fun install(input: InputStream, dir: File) = SherpaModel.install(input, dir)

    fun speaker(dir: File, male: Boolean, rate: Float): Speaker =
        SherpaSpeaker(dir, if (male) SherpaModel.AISHELL3_MALE else SherpaModel.AISHELL3_FEMALE, rate)
}
