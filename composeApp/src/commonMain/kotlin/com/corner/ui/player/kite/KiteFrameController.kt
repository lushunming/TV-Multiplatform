package com.corner.ui.player.kite

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.corner.database.entity.History
import com.corner.ui.nav.vm.DetailViewModel
import com.corner.ui.player.PlayerController
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("KiteFrameController")

/**
 * KitePlayer-backed controller. Delegates all [PlayerController] work to [KiteController] and
 * exposes the Compose video surface ([Video][KiteVideo]) plus the history glue the old
 * VlcjFrameController provided.
 */
class KiteFrameController(
    component: DetailViewModel,
    private val controller: KiteController = KiteController(component),
) : PlayerController by controller {

    private var historyCollectJob: Job? = null

    @Volatile
    var isReleased = true
        private set

    override fun load(url: String): PlayerController {
        controller.load(url)
        speed(controller.history.value?.speed?.toFloat() ?: 1f)
        controller.play()
        val pos = maxOf(controller.history.value?.position ?: 0L, controller.history.value?.opening ?: 0L)
        if (pos > 0) seekTo(pos)
        return controller
    }

    override fun init() {
        log.info("播放器初始化")
        controller.init()
        isReleased = false
    }

    fun isPlaying(): Boolean {
        if (isReleased) return false
        val status = controller.player?.state?.value?.status ?: return false
        return status == io.github.yuroyami.kiteplayer.PlaybackStatus.Playing
    }

    fun setStartEnd(opening: Long, ending: Long) {
        controller.setStartEnding(opening, ending)
    }

    fun setControllerHistory(history: History) {
        controller.scope.launch {
            controller.history.emit(history)
        }
        if (historyCollectJob != null) return
        historyCollectJob = controller.scope.launch {
            delay(10)
            controller.history.collect {
                if (it != null) {
                    controller.vm.updateHistory(it)
                }
            }
        }
    }

    fun getControllerHistory(): History? {
        return controller.history.value
    }

    fun doWithHistory(func: (History) -> History) {
        runBlocking {
            if (controller.history.value == null) return@runBlocking
            controller.history.emit(func(controller.history.value!!))
        }
    }

    fun getPlayer(): KitePlayer? {
        return controller.player
    }

    /**
     * The video surface. Call inside a Box in place of the old FrameContainer's Canvas draw:
     * `controller.Video(Modifier.fillMaxSize())`.
     */
    @Composable
    fun Video(modifier: Modifier = Modifier) {
        controller.player?.let { player ->
            KitePlayerVideo(player = player, modifier = modifier)
        }
    }

    fun release() {
        controller.dispose()
        isReleased = true
    }
}
