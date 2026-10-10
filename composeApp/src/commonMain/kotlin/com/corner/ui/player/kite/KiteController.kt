package com.corner.ui.player.kite

import com.corner.catvod.enum.bean.Vod
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.entity.History
import com.corner.ui.nav.vm.DetailViewModel
import com.corner.ui.player.MediaInfo
import com.corner.ui.player.PlayState
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerState
import com.corner.ui.scene.SnackBar
import com.corner.util.catch
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("KiteController")

class KiteController(val vm: DetailViewModel) : PlayerController {
    var player: KitePlayer? = null
        private set

    private val defferredEffects = mutableListOf<(KitePlayer) -> Unit>()

    private var isAccelerating = false
    private var originSpeed = 1.0F
    private var currentSpeed = 1.0F
    private var playerReady = false

    private val disposed = AtomicBoolean(false)

    override var showTip = MutableStateFlow(false)
    override var tip = MutableStateFlow("")
    override var history: MutableStateFlow<History?> = MutableStateFlow(null)
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(PlayerState())

    override val state: StateFlow<PlayerState>
        get() = _state.asStateFlow()

    override fun doWithMediaPlayer(block: (io.github.yuroyami.kiteplayer.KitePlayer) -> Unit) {
        player?.let {
            block(it)
        } ?: run {
            defferredEffects.add(block)
        }
    }

    override fun onMediaPlayerReady(mediaPlayer: io.github.yuroyami.kiteplayer.KitePlayer) {
        this.player = mediaPlayer
        _state.update { it.copy(duration = mediaPlayer.state.value.duration?.inWholeMilliseconds ?: 0L) }
        defferredEffects.forEach { block ->
            block(mediaPlayer)
        }
        defferredEffects.clear()
    }

    override fun init() {
        if (player != null) return
        catch {
            log.info("初始化 KitePlayer")
            val kite = KitePlayer()
            player = kite
            collectPlayerEvents(kite)
            collectProgress(kite)
            collectSnapshot(kite)
        }
    }

    private fun collectProgress(kite: KitePlayer) {
        scope.launch {
            kite.progress.collect { p ->
                val positionMs = p.position.inWholeMilliseconds
                _state.update { it.copy(timestamp = positionMs) }
                val hist = history.value
                if (hist == null) {
                    println("history is null")
                    return@collect
                }
                if (hist.ending != null && hist.ending != -1L && hist.ending <= positionMs) vm.nextEP()
                if ((positionMs / 1000 % 25).toInt() == 0) history.emit(hist.copy(position = positionMs))
            }
        }
    }

    private fun collectSnapshot(kite: KitePlayer) {
        scope.launch {
            kite.state.collect { snap ->
                when(snap.status){
                    PlaybackStatus.Buffering->{
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.BUFFERING
                            )
                        }
                    }

                    PlaybackStatus.Idle -> {
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.BUFFERING
                            )
                        }
                    }
                    PlaybackStatus.Opening -> {
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.BUFFERING
                            )
                        }
                    }
                    PlaybackStatus.Playing -> {
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.PLAY
                            )
                        }
                    }
                    PlaybackStatus.Paused -> {
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.PAUSE
                            )
                        }

                    }
                    PlaybackStatus.Ended -> {
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.BUFFERING
                            )
                        }
                    }
                    PlaybackStatus.Failed -> {
                        _state.update {
                            it.copy(
                                duration = snap.duration?.inWholeMilliseconds ?: it.duration,
                                volume = snap.volume,
                                isMuted = snap.muted,
                                speed = snap.speed.toFloat(),
                                state = PlayState.ERROR
                            )
                        }
                    }
                }

            }
        }
    }

    private fun collectPlayerEvents(kite: KitePlayer) {
        scope.launch {
            kite.events.collect { event ->
                when (event) {
                    is PlayerEvent.Opened -> {
                        log.info("媒体打开完成")
                        playerReady = true
                        _state.update {
                            it.copy(
                                duration = kite.state.value.duration?.inWholeMilliseconds ?: it.duration,
                                state = PlayState.BUFFERING
                            )
                        }
                        play()
                    }

                    is PlayerEvent.VideoSizeChanged -> {
                        val size = event.size
                        _state.update {
                            it.copy(
                                mediaInfo = MediaInfo(
                                    url = kite.state.value.media?.uri ?: "",
                                    width = size.width,
                                    height = size.height
                                )
                            )
                        }
                    }

                    is PlayerEvent.Ended -> {
                        println("finished")
                        _state.update { it.copy(state = PlayState.PAUSE) }
                        try {
                            vm.nextEP()
                        } catch (e: Exception) {
                            log.error("finished error", e)
                        }
                    }

                    is PlayerEvent.Failed -> {
                        log.error("播放错误: ${event.error}")
                        _state.update { it.copy(state = PlayState.ERROR, msg = "播放错误") }
                        vm.nextFlag()
                        history.value?.let { vm.updateHistory(it) }
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun load(url: String): PlayerController {
        log.debug("加载：$url")
        if (StringUtils.isBlank(url)) {
            SnackBar.postMsg("播放地址为空")
            return this
        }
        val kite = player ?: return this
        catch {
            scope.launch {
                try {
                    if (kite.state.value.status != PlaybackStatus.Idle) {
                        kite.stop()
                    }
                    kite.open(
                        MediaItem(
                            uri = url,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
                            )
                        )
                    )
                } catch (e: Exception) {
                    log.error("打开媒体失败: $url", e)
                    _state.update { it.copy(state = PlayState.ERROR, msg = "播放错误") }
                }
            }
        }
        return this
    }

    override fun play() {
        catch {
            log.debug("play")
            showTips("播放")
            player?.play()
        }
    }

    override fun play(url: String) = catch {
        showTips("播放")
        log.debug("play: $url")
        load(url)
    }

    override fun pause() = catch {
        showTips("暂停")
        player?.pause()
    }

    private fun showTips(text: String) {
        scope.launch {
            tip.emit(text)
            showTip.emit(true)
        }
    }

    override fun stop() = catch {
        showTips("停止")
        scope.launch {
            player?.stop()
        }
    }

    override fun dispose() = catch {
        log.debug("dispose")
        if (disposed.compareAndSet(false, true)) {
            scope.launch {
                player?.stop()
                player?.close()
                player = null
            }
        }
    }

    override fun seekTo(timestamp: Long) = catch {
        _state.update { it.copy(timestamp = timestamp) }
        scope.launch {
            player?.requestSeek(timestamp.milliseconds)
        }
    }

    override fun setVolume(value: Float) = catch {
        val clamped = value.coerceIn(0f, 1.5f)
        player?.setVolume(clamped.coerceAtMost(1f))
        _state.update { it.copy(volume = clamped) }
        showTips("音量：${(clamped * 100).toInt()}")
    }

    private val volumeStep = 0.05f

    override fun volumeUp() {
        val current = player?.state?.value?.volume ?: 0.8f
        val next = (current + volumeStep).coerceIn(0f, 1f)
        setVolume(next)
    }

    override fun volumeDown() {
        val current = player?.state?.value?.volume ?: 0.8f
        val next = (current - volumeStep).coerceIn(0f, 1f)
        setVolume(next)
    }

    override fun forward(time: String) {
        showTips("快进：$time")
        val delta = Duration.parse(time)
        catch {
            val current = player?.position() ?: Duration.ZERO
            val target = current + delta
            player?.requestSeek(target)
            _state.update { it.copy(timestamp = target.inWholeMilliseconds) }
        }
    }

    override fun backward(time: String) {
        showTips("快退：$time")
        val delta = Duration.parse(time)
        catch {
            val current = player?.position() ?: Duration.ZERO
            val target = (current - delta).coerceAtLeast(Duration.ZERO)
            player?.requestSeek(target)
            _state.update { it.copy(timestamp = target.inWholeMilliseconds) }
        }
    }

    override fun toggleSound() = catch {
        val muted = player?.state?.value?.muted ?: false
        player?.setMuted(!muted)
    }

    override fun toggleFullscreen() = catch {
        val videoFullScreen = GlobalAppState.toggleVideoFullScreen()
        if (videoFullScreen) showTips("[ESC]退出全屏")
    }

    override fun togglePlayStatus() {
        val status = player?.state?.value?.status
        if (status == PlaybackStatus.Playing) {
            pause()
        } else {
            play()
        }
    }

    override fun speed(speed: Float) = catch {
        showTips("倍速：$speed")
        player?.setSpeed(speed.toDouble())
    }

    override fun stopForward() {
        isAccelerating = false
        speed(originSpeed)
    }

    override fun fastForward() {
        if (!isAccelerating) {
            currentSpeed = player?.state?.value?.speed?.toFloat() ?: 1.0f
            originSpeed = currentSpeed.toDouble().toFloat()
            isAccelerating = true
        }
        acceleratePlayback()
    }

    private val maxSpeed = 4.0f

    private fun acceleratePlayback() {
        if (isAccelerating) {
            currentSpeed += 0.5f
            currentSpeed = Math.min(currentSpeed, maxSpeed)
            speed(currentSpeed)
            println("Playback rate: $currentSpeed x")
        }
    }

    override fun updateEnding(detail: Vod?) {
        val time = player?.position()?.inWholeMilliseconds ?: -1
        _state.update { it.copy(ending = time) }
        history.update { it?.copy(ending = time) }
    }

    override fun updateOpening(detail: Vod?) {
        val time = player?.position()?.inWholeMilliseconds ?: -1
        _state.update { it.copy(opening = time) }
        history.update { it?.copy(opening = time) }
    }

    override fun doWithPlayState(func: (MutableStateFlow<PlayerState>) -> Unit) {
        func(_state)
    }

    override fun setStartEnding(opening: Long, ending: Long) {
        _state.update { it.copy(opening = opening, ending = ending) }
    }
}
