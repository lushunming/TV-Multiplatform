package com.corner.ui.player.frame

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.ui.player.PlayState
import com.corner.ui.player.mediamp.MediampFrameController
import org.jetbrains.compose.resources.painterResource
import org.openani.mediamp.compose.MediampPlayerSurface
import tv_multiplatform.composeapp.generated.resources.Res
import tv_multiplatform.composeapp.generated.resources.TV_icon_x

class ArrowKeyState {
    var pressAt = 0L
    var holding = false
    var lastActionAt = 0L

    fun reset() {
        pressAt = 0L
        holding = false
        lastActionAt = 0L
    }
}

const val HOLD_THRESHOLD_MS = 300L
const val REWIND_INTERVAL_MS = 150L

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, InternalFoundationApi::class)
@Composable
fun FrameContainer(
    modifier: Modifier = Modifier,
    controller: MediampFrameController,
    onClick: () -> Unit
) {
    val playerState = controller.state.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val rightKey = remember { ArrowKeyState() }
    val leftKey = remember { ArrowKeyState() }
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
                Key.DirectionRight -> when (k.type) {
                    KeyEventType.KeyDown -> {
                        val now = System.currentTimeMillis()
                        if (rightKey.pressAt == 0L) {
                            rightKey.pressAt = now
                        } else if (now - rightKey.pressAt >= HOLD_THRESHOLD_MS) {
                            rightKey.holding = true
                            controller.fastForward()
                        }
                    }
                    KeyEventType.KeyUp -> {
                        if (rightKey.holding) {
                            controller.stopForward()
                        } else if (System.currentTimeMillis() - rightKey.pressAt < HOLD_THRESHOLD_MS) {
                            controller.forward()
                        }
                        rightKey.reset()
                    }
                }

                Key.DirectionLeft -> when (k.type) {
                    KeyEventType.KeyDown -> {
                        val now = System.currentTimeMillis()
                        if (leftKey.pressAt == 0L) {
                            leftKey.pressAt = now
                            leftKey.lastActionAt = now
                            controller.backward()
                        } else if (now - leftKey.lastActionAt >= REWIND_INTERVAL_MS) {
                            leftKey.lastActionAt = now
                            controller.backward()
                        }
                    }
                    KeyEventType.KeyUp -> leftKey.reset()
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
            controller.player?.let { p ->
                MediampPlayerSurface(p, Modifier.fillMaxSize())
            }
            when (playerState.value.state) {
                PlayState.BUFFERING -> {
                    ProgressIndicator(
                        Modifier.align(Alignment.Center),
                        progression = playerState.value.bufferProgression
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
                    // No bitmap fallback needed - MediampPlayerSurface handles rendering
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
                    offset = Offset(8f, 8f),
                    blurRadius = 8f
                ),
                fontWeight = FontWeight.Bold
            )
        )
    }
}
