package com.wildcreator.sonorus.data.background

import java.io.File
import java.io.IOException
import java.io.InputStream

const val DEFAULT_BACKGROUND_OPACITY = 0.25f
const val MAX_BACKGROUND_BYTES = 20L * 1024 * 1024

data class ChatBackgroundSettings(
    val path: String? = null,
    val opacity: Float = DEFAULT_BACKGROUND_OPACITY
) {
    companion object {
        fun fromStored(path: String?, opacity: Float?) = ChatBackgroundSettings(
            path = path?.takeUnless { it.isBlank() },
            opacity = opacity?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ?: DEFAULT_BACKGROUND_OPACITY
        )
    }
}

internal class BackgroundImageException(message: String) : IOException(message)

internal fun importBackgroundFile(
    directory: File,
    openSource: () -> InputStream,
    validate: (File) -> Unit,
    maxBytes: Long = MAX_BACKGROUND_BYTES
): File {
    require(maxBytes > 0)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建背景存储目录")
    val target = File.createTempFile("background-", ".image", directory).absoluteFile
    var complete = false
    try {
        openSource().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > maxBytes) throw BackgroundImageException("图片过大，请选择不超过 20 MB 的图片")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw BackgroundImageException("图片为空，请重新选择")
                output.fd.sync()
            }
        }
        validate(target)
        complete = true
        return target
    } finally {
        if (!complete) target.delete()
    }
}

internal fun backgroundSampleSize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) throw BackgroundImageException("无法解码图片，请选择有效的图片")
    var sample = 1
    val edge = maxOf(width, height).toLong()
    while ((edge + sample - 1) / sample > 2048) sample *= 2
    return sample
}
