package com.wildcreator.sonorus.data.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.wildcreator.sonorus.data.prefs.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatBackgroundStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsManager
) {
    private val mutex = Mutex()
    private val directory get() = File(context.noBackupFilesDir, "chat-background")

    suspend fun importImage(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val image = importBackgroundFile(
                directory = directory,
                openSource = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw BackgroundImageException("无法读取所选图片，请重新选择")
                },
                validate = { loadChatBackgroundBitmap(it.absolutePath).recycle() }
            )
            var committed = false
            try {
                ensureActive()
                withContext(NonCancellable) {
                    settings.saveChatBackgroundPath(image.absolutePath)
                    committed = true
                    deleteUnusedImages(image)
                }
            } finally {
                if (!committed) image.delete()
            }
        }
    }

    suspend fun saveOpacity(opacity: Float) = mutex.withLock {
        settings.saveChatBackgroundOpacity(opacity)
    }

    suspend fun restoreDefault() = withContext(Dispatchers.IO) {
        mutex.withLock {
            withContext(NonCancellable) {
                settings.resetChatBackground()
                deleteUnusedImages(null)
            }
        }
    }

    private fun deleteUnusedImages(keep: File?) {
        directory.listFiles()?.filter { it != keep }?.forEach { file ->
            runCatching { file.delete() }
        }
    }
}

internal fun loadChatBackgroundBitmap(path: String): Bitmap {
    try {
        val file = File(path)
        if (!file.isFile || file.length() !in 1..MAX_BACKGROUND_BYTES) {
            throw BackgroundImageException("背景图片不存在或已损坏，请重新选择")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = backgroundSampleSize(bounds.outWidth, bounds.outHeight)
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
            ?: throw BackgroundImageException("无法解码图片，请选择有效的图片")
    } catch (error: OutOfMemoryError) {
        throw IOException("图片解码内存不足，请选择较小的图片", error)
    }
}
