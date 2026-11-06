package com.corner.ui.scene

import AppTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

object SnackBar {

    private const val DISPLAY_MS = 3000L
    private const val IDLE_POLL_MS = 100L

    private val msgQueue = ConcurrentLinkedQueue<String>()
    private val msgList = MutableList(2) { MutableStateFlow("") }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // 唯一的消费协程：有空槽位时取一条消息，显示超时后由子协程清空自己那一条。
        // 原实现每次 postMsg 都新起 2 个协程抢同一把 Semaphore（许可耗尽后永久阻塞），
        // 且 while(true) 在值非空时没有任何挂起点，会造成忙等烧 CPU。
        scope.launch {
            while (isActive) {
                val slot = msgList.indexOfFirst { it.value.isBlank() }
                if (slot == -1) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val msg = msgQueue.poll()
                if (msg == null) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                show(slot, msg)
            }
        }
    }

    private fun show(slot: Int, msg: String) {
        msgList[slot].value = msg
        scope.launch {
            delay(DISPLAY_MS)
            // 槽位可能已被更晚的消息占用，只清理自己显示的那条
            if (msgList[slot].value == msg) msgList[slot].value = ""
        }
    }

    @Composable
    fun SnackBarItem(index: Int) {
        val msg = msgList[index].collectAsState()
        AnimatedVisibility(
            visible = msg.value.trim().isNotBlank(),
            enter = slideInVertically(
                initialOffsetY = { fullHeight -> -fullHeight },
                animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing)
            ),
            exit = slideOutVertically(
                targetOffsetY = { fullHeight -> -fullHeight },
                animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing)
            )
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Snackbar(
                    modifier = Modifier.padding(top = 5.dp, start = 10.dp, end = 10.dp)
                        .height(60.dp)
                        .wrapContentWidth()
//                        .width((35 * msg.value.length).dp)
                        .align(Alignment.Center),
                    shape = RoundedCornerShape(25),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    action = {
                        Box(modifier = Modifier.fillMaxHeight().wrapContentWidth()) {
                            Icon(
                                imageVector = Icons.Outlined.Close, contentDescription = "Close the SnackBar",
                                modifier = Modifier.clickable(onClick = { msgList[index].value = "" })
                                    .align(Alignment.Center),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                ) {
                    Text(msg.value, modifier = Modifier.align(Alignment.Center).fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }

    @Composable
    fun SnackBarList() {
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.aligned(Alignment.Bottom)
        ) {
            repeat(msgList.size) {
                SnackBarItem(it)
            }
        }
    }

    fun postMsg(msg: String) {
        if (msgQueue.size > 6) {
            msgQueue.poll()
        }
        msgQueue.add(msg)
    }

}

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun previewSnackBar() {
    AppTheme {
        SnackBar.postMsg("TEST")
        SnackBar.SnackBarItem(0)
    }
}