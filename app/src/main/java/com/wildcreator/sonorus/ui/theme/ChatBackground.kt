package com.wildcreator.sonorus.ui.theme

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.wildcreator.sonorus.data.background.ChatBackgroundSettings
import com.wildcreator.sonorus.data.background.loadChatBackgroundBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalChatBackground = staticCompositionLocalOf { ChatBackgroundSettings() }

@Composable
fun ChatBackground(
    modifier: Modifier = Modifier,
    background: ChatBackgroundSettings = LocalChatBackground.current,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onError)
    val normalized = ChatBackgroundSettings.fromStored(background.path, background.opacity)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        normalized.path?.let { path ->
            key(path) {
                var error by remember { mutableStateOf<String?>(null) }
                val bitmap by produceState<Bitmap?>(initialValue = null) {
                    try {
                        value = withContext(Dispatchers.IO) { loadChatBackgroundBitmap(path) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "无法读取聊天背景，已显示默认背景；请到设置中重新选择图片"
                    }
                }
                LaunchedEffect(error) {
                    error?.let { message ->
                        val callback = currentOnError
                        if (callback != null) callback(message)
                        else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
                bitmap?.let { image ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = normalized.opacity)))
                }
            }
        }
    }
}
