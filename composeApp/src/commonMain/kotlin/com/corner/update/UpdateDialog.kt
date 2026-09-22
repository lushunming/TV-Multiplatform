package com.corner.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.corner.ui.scene.Dialog
import com.corner.update.TvAppUpdater.updater
import com.pavi2410.appupdater.UpdateState
import com.pavi2410.appupdater.ui.UpdateCard

/**
 * 应用启动后检查更新，发现新版本时弹出的更新弹窗。
 *
 * UI 直接使用官方的 [UpdateCard]，只负责把它包进项目里通用的 [Dialog] 容器。
 *
 * @param showDialog 是否显示，默认为 null 时由组件内部检查更新后自动弹出
 * @param onClose 关闭弹窗（用户取消更新时调用）
 */
@Composable
fun WindowScope.UpdateDialog(
    showDialog: Boolean? = null,
    onClose: () -> Unit = {},
) {
    var checked by remember { mutableStateOf(false) }
    var internalShow by remember { mutableStateOf(false) }

    if (showDialog == null) {
        // 自动模式：启动后检查一次更新
        LaunchedEffect(Unit) {
            runCatching {
                val release = updater.checkForUpdate()
                if (release != null) internalShow = true
            }.onFailure {
                it.printStackTrace()
            }
            checked = true
        }
    }

    val show = showDialog ?: internalShow
    if (!show) return

    Dialog(
        modifier = Modifier
            .padding(20.dp),
        showDialog = true,
        onClose = {
            // 下载中不允许点击外部关闭，避免误触中断下载
            val state = updater.state.value
            if (state is UpdateState.Downloading) return@Dialog
            internalShow = false
            onClose()
        },
    ) {
        Surface(
            modifier = Modifier
                .shadow(2.dp, shape = RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(modifier = Modifier.padding(4.dp)) {
                UpdateCard(updater = updater)
            }
        }
    }
}
