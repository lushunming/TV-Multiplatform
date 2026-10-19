package com.corner.ui.player.mediamp

import com.corner.database.entity.History
import com.corner.ui.nav.vm.DetailViewModel
import com.corner.ui.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openani.mediamp.MediampPlayer

class MediampFrameController(
    component: DetailViewModel,
    private val controller: MediampController = MediampController(component),
) : PlayerController by controller {

    val player: MediampPlayer? get() = controller.player

    var isReleased = true
        private set

    private var historyCollectJob: Job? = null

    override fun load(url: String): PlayerController {
        // speed 和 play 都在 MediampController.load() 里 setMediaData 完成后顺序执行，
        // 避免在媒体打开前就调用播放命令导致时序问题。
        controller.load(url)
        return controller
    }

    override fun init() {
        controller.init()
        isReleased = false
    }

    fun isPlaying(): Boolean {
        return !isReleased && controller.player?.state?.value?.isPlaying == true
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

    fun release() {
        // 播放器关闭完成后再 cancel scope，回收所有观察协程（state/position/volume/events…）。
        // 原来 scope 永不取消，每次进出详情页都会泄漏一批常驻协程
        controller.scope.launch(Dispatchers.Main) {
            controller.player?.stopPlayback()
            // 退出前把最后的播放位置补写一次库：
            // 周期写库间隔 25s，直接退出会丢掉最后不足 25s 的进度（“上次看到”就靠它恢复）
            val lastPos = controller.player?.currentPositionMillis?.value ?: controller.history.value?.position ?: 0L
            controller.history.value?.let { h ->
                controller.history.emit(h.copy(position = lastPos))
            }
            controller.player?.close()
        }.invokeOnCompletion {
            controller.scope.cancel()
        }
        historyCollectJob = null // 置空以便再次 init() 时重建 history 收集器
        isReleased = true
    }
}
