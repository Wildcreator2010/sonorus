package com.wildcreator.sonorus.ui.donate

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.wildcreator.sonorus.R

/**
 * 自愿捐赠：两张收款码竖排展示。
 *
 * 图片是用户提供的原图，直接放进 `drawable-nodpi`：
 * - nodpi 是为了不让系统按屏幕密度缩放 —— 二维码被插值放大就扫不出来；
 * - 不裁切、不改色、不加圆角与遮罩，任何装饰都会伤到码点。
 */
@Composable
fun DonateDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("自愿捐赠", style = MaterialTheme.typography.titleLarge)
                Text(
                    "觉得好用的话可以扫个码，金额随意。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                QrImage(
                    resId = R.drawable.donate_alipay,
                    label = "支付宝"
                )
                QrImage(
                    resId = R.drawable.donate_wechat,
                    label = "微信支付"
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun QrImage(resId: Int, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(
            painter = painterResource(resId),
            contentDescription = label,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
