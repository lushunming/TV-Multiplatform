package com.corner.ui.player.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isTypedEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.ui.player.PlayState
import com.corner.ui.player.kite.KiteFrameController
import org.jetbrains.compose.resources.painterResource
import tv_multiplatform.composeapp.generated.resources.Res
import tv_multiplatform.composeapp.generated.resources.TV_icon_x

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FrameContainer(
    modifier: Modifier = Modifier,
    controller: KiteFrameController,
    onClick: () -> Unit
) {
    val playerState = controller.state.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier.background(Color.Black)
        .combinedClickable(
            enabled = true,
            onDoubleClick = {
                controller.togglePlayStatus()
            },
            interactionSource = interactionSource,
            indication = null
        ) {
            // onClick
            onClick()
        }
        .onPointerEvent(PointerEventType.Scroll) { e ->
            val y = e.changes.first().scrollDelta.y
            if (y < 0) {
                controller.volumeUp()
            } else {
                controller.volumeDown()
            }
        }
        .onKeyEvent { k ->
            when (k.key) {
                Key.DirectionRight -> {
                    if (k.type == KeyEventType.KeyDown) {
                        controller.fastForward()
                    } else if (k.type == KeyEventType.KeyUp) {
                        controller.stopForward()
                    }
                    if (k.isTypedEvent) {
                        controller.forward()
                    }
                }

                Key.DirectionLeft -> {
                    if (k.isTypedEvent) {
                        controller.backward()
                    }
                }

                Key.Spacebar -> if (k.type == KeyEventType.KeyDown) controller.togglePlayStatus()
                Key.DirectionUp -> if (k.type == KeyEventType.KeyDown) controller.volumeUp()
                Key.DirectionDown -> if (k.type == KeyEventType.KeyDown) controller.volumeDown()
                Key.Escape -> if (k.type == KeyEventType.KeyDown && GlobalAppState.videoFullScreen.value) controller.toggleFullscreen()
            }
            true
        }, contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // KitePlayer draws the video frames as true Compose content here.
            controller.Video(Modifier.fillMaxSize())
            when (playerState.value.state) {
                PlayState.BUFFERING -> {
                    ProgressIndicator(
                        Modifier.align(Alignment.Center)
                    )
                }

                PlayState.ERROR -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "error icon",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(playerState.value.msg, color = MaterialTheme.colorScheme.primary)
                    }
                }

                else -> {
                    if (playerState.value.mediaInfo == null) {
                        Image(
                            modifier = Modifier.align(Alignment.Center).size(120.dp),
                            painter = painterResource(Res.drawable.TV_icon_x),
                            contentDescription = "nothing here",
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProgressIndicator(modifier: Modifier, text: String = "加载中...", progression: Float = -1f) {
    Column(modifier) {
        if (progression != -1f) {
            CircularProgressIndicator(
                progress = { progression / 100},
            )
        } else {
            CircularProgressIndicator()
        }
        Text(
            if (progression != -1f) "%.2f".format(progression) + "%" else text, style = TextStyle(
                color = MaterialTheme.colorScheme.primary, shadow = Shadow(
                    color = Color.Black,
                    offset = androidx.compose.ui.geometry.Offset(8f, 8f),
                    blurRadius = 8f
                ),
                fontWeight = FontWeight.Bold
            )
        )
    }
}
