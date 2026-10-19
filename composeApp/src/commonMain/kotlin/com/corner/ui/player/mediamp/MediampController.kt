package com.corner.ui.player.mediamp

import com.corner.bean.PlayerStateCache
import com.corner.bean.SettingStore
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apache.commons.lang3.StringUtils
import org.openani.mediamp.MediaLoadCancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.UriMediaData
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val log = LoggerFactory.getLogger("MediampController")

/** 播放进度写历史库的最小间隔（毫秒） */
private const val HISTORY_SAVE_INTERVAL_MS = 25_000L

class MediampController(val vm: DetailViewModel) : PlayerController {

    var player: MediampPlayer? = null
        private set

    private var isAccelerating = false
    private var originSpeed = 1.0F
    private var currentSpeed = 1.0F

    private var lastHistorySaveAt = 0L

    override var showTip = MutableStateFlow(false)
    override var tip = MutableStateFlow("")
    override var history: MutableStateFlow<History?> = MutableStateFlow(null)
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState>
        get() = _state.asStateFlow()

    override fun init() {
        try {
            // 每次初始化重建 scope：release() 会 cancel 旧 scope 以回收观察协程，
            // 这里重建才能保证 init() 可再次执行
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val p =
                MpvMediampPlayer(Unit, scope.coroutineContext) { handle ->
                    handle.option("demuxer-max-bytes", "${256 * 1024 * 1024}")
                    handle.option("cache-secs", "120")
                }
            player = p
            log.info("MediampPlayer 创建成功: $p, impl=${p.impl}")
            startObservingPlayer(p)
        } catch (e: Throwable) {
            log.error("MediampPlayer 创建失败", e)
        }
    }

    private fun startObservingPlayer(player: MediampPlayer) {

            scope.launch {
                while (isActive) {
                    try {
                        val buffering = player.features[Buffering]
                        // 打开播放器后
                        buffering?.bufferedPositionMillis?.collect { pos ->
                            val ms = if (pos == Buffering.UNKNOWN_POSITION) 0L else pos
                            // 更新你的 StateFlow / MutableState
                            _state.update { it.copy(cachedTimestamp = ms) }
                        }
                    } catch (e: Throwable) {
                        log.debug("read demuxer-cache-time failed", e)
                    }
                    delay(1000)
                }
            }


        // Observe state changes
        scope.launch {
            player.state.collect { mpState ->
                val playState = when {
                    mpState.mediaStatus == MediaStatus.Opening || mpState.isBuffering -> PlayState.BUFFERING
                    mpState.mediaStatus == MediaStatus.Ready && mpState.playWhenReady && !mpState.isBuffering -> PlayState.PLAY
                    mpState.mediaStatus == MediaStatus.Ready && !mpState.playWhenReady -> PlayState.PAUSE
                    mpState.mediaStatus == MediaStatus.Ended -> PlayState.PAUSE
                    mpState.mediaStatus is MediaStatus.Error -> PlayState.ERROR
                    else -> PlayState.PAUSE
                }
                _state.update { it.copy(state = playState) }
            }
        }

        // Observe media properties (duration, video dimensions)
        scope.launch {
            player.mediaProperties.collect { props ->
                props?.let { mp ->
                    _state.update { state ->
                        state.copy(
                            duration = mp.durationMillis ?: 0L,
                            mediaInfo = if (mp.videoWidth != null && mp.videoHeight != null) {
                                val h = mp.videoHeight ?: 0
                                val w = mp.videoWidth ?: 0
                                MediaInfo(
                                    url = player.mediaData.value?.let { md ->
                                        if (md is UriMediaData) md.uri else ""
                                    } ?: "",
                                    height = h,
                                    width = w
                                )
                            } else state.mediaInfo
                        )
                    }
                }
            }
        }

        // Observe current position
        scope.launch {
            player.currentPositionMillis.collect { pos ->
                _state.update { it.copy(timestamp = pos) }
                // Check ending skip
                history.value?.let { h ->
                    if (h.ending != null && h.ending != -1L && h.ending <= pos) {
                        vm.nextEP()
                    }
                }
                // Save history position periodically
                history.value?.let { h ->
                    // 按墙钟时间距上次保存超过 25s 才写库。
                    // 原来用 (pos / 1000 % 25).toInt() == 0 判断：位置更新每秒触发几十次，
                    // 同一秒内会连续命中多次（seek 后尤其明显），造成连续写库
                    val now = System.currentTimeMillis()
                    if (now - lastHistorySaveAt >= HISTORY_SAVE_INTERVAL_MS) {
                        lastHistorySaveAt = now
                        history.emit(h.copy(position = pos))
                    }
                }
            }
        }

        // Observe events (MediaEnded, ErrorOccurred)
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    is PlaybackEvent.MediaEnded -> {
                        scope.launch {
                            try {
                                if (checkEnd()) return@launch
                                vm.nextEP()
                            } catch (e: Exception) {
                                log.error("MediaEnded error", e)
                            }
                        }
                    }
                    is PlaybackEvent.ErrorOccurred -> {
                        log.error("播放错误: ${player.mediaData.value}")
                        _state.update { it.copy(state = PlayState.ERROR, msg = "播放错误") }
                        vm.nextFlag()
                        scope.launch {
                            history.value?.let { vm.updateHistory(it) }
                            try {
                                if (checkEnd()) return@launch
                            } catch (e: Exception) {
                                log.error("error ", e)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // Observe volume from AudioLevelController feature
        scope.launch {
            val audioController = player.features[AudioLevelController]
            audioController?.volume?.collect { vol ->
                if (vol > 0f) {
                    SettingStore.doWithCache {
                        var cacheState = it["playerState"]
                        if (cacheState == null) {
                            cacheState = PlayerStateCache()
                            it["playerState"] = cacheState
                        }
                        (cacheState as PlayerStateCache).add("volume", vol.toString())
                    }
                }
                _state.update { it.copy(volume = vol) }
            }
        }

        // Observe mute state
        scope.launch {
            val audioController = player.features[AudioLevelController]
            audioController?.isMute?.collect { muted ->
                _state.update { it.copy(isMuted = muted) }
            }
        }
    }

    private fun checkEnd(): Boolean {
        try {
            val props = player?.mediaProperties?.value
            val duration = props?.durationMillis ?: 0L
            if (duration <= 0) return false
            return false
        } catch (e: Exception) {
            log.error("checkEnd error:", e)
            return false
        }
    }

    override fun load(url: String): PlayerController {
        log.debug("加载：$url")
        if (StringUtils.isBlank(url)) {
            SnackBar.postMsg("播放地址为空")
            return this
        }
        scope.launch {
            try {
                val p = player ?: return@launch
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
                )
                val h = history.value
                val startPos = maxOf(h?.position ?: 0L, h?.opening ?: 0L)
                // setMediaData 挂起直到媒体真正打开；正常返回后进入 Ready 状态
                p.setMediaData(
                    UriMediaData(url, headers),
                    playWhenReady = true,
                    startPositionMillis = startPos
                )
                // 在主线程上恢复历史倍速并显式开始播放（播放命令必须运行在 mainDispatcher）
                withContext(Dispatchers.Main) {
                    p.features[PlaybackSpeed]?.set(h?.speed?.toFloat() ?: 1f)
                    p.play()
                }
            } catch (e: MediaLoadCancellationException) {
                log.info("本次加载被新的加载取代，忽略: $url")
            } catch (e: CancellationException) {
                throw e   // 协程自身的取消要继续上抛，不要吞
            }catch (e: Exception) {
                log.error("加载媒体失败", e)
                _state.update { it.copy(state = PlayState.ERROR, msg = "加载失败: ${e.message}") }
            }
        }
        return this
    }

    private inline fun runOnMain(crossinline block: suspend () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    override fun play() {
        catch {
            log.debug("play")
            showTips("播放")
            runOnMain { player?.play() }
        }
    }

    override fun play(url: String) = catch {
        showTips("播放")
        log.debug("play: $url")
        scope.launch {
            val p = player ?: return@launch
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
            )
            p.setMediaData(UriMediaData(url, headers), playWhenReady = true)
        }
    }

    override fun pause() = catch {
        showTips("暂停")
        runOnMain { player?.pause() }
    }

    override fun stop() = catch {
        showTips("停止")
        runOnMain { player?.stopPlayback() }
    }

    override fun dispose() = catch {
        log.debug("dispose")
        runBlocking(Dispatchers.Main) {
            player?.stopPlayback()
            player?.close()
        }
        scope.cancel()
    }

    override fun seekTo(timestamp: Long) = catch {
        _state.update { it.copy(timestamp = timestamp) }
        runOnMain { player?.seekTo(timestamp) }
    }

    override fun setVolume(value: Float) = catch {
        runOnMain { player?.features?.get(AudioLevelController)?.setVolume(value) }
        _state.update { it.copy(volume = value) }
        showTips("音量：${(value * 100).toInt()}")
    }

    private val volumeStep = 0.05f

    override fun volumeUp() {
        runOnMain {
            player?.features?.get(AudioLevelController)?.volumeUp(volumeStep)
        }
        val vol = player?.features?.get(AudioLevelController)?.volume?.value ?: 0.8f
        _state.update { it.copy(volume = vol) }
        showTips("音量：${(vol * 100).toInt()}")
    }

    override fun volumeDown() {
        runOnMain {
            player?.features?.get(AudioLevelController)?.volumeDown(volumeStep)
        }
        val vol = player?.features?.get(AudioLevelController)?.volume?.value ?: 0.8f
        _state.update { it.copy(volume = vol) }
        showTips("音量：${(vol * 100).toInt()}")
    }

    override fun forward(time: String) {
        showTips("快进：$time")
        val delta = Duration.parse(time).toLong(DurationUnit.MILLISECONDS)
        runOnMain {
            player?.skip(delta)
            _state.update { it.copy(timestamp = player?.currentPositionMillis?.value ?: 0L) }
        }
    }

    override fun backward(time: String) {
        showTips("快退：$time")
        val delta = -Duration.parse(time).toLong(DurationUnit.MILLISECONDS)
        runOnMain {
            player?.skip(delta)
            _state.update { it.copy(timestamp = player?.currentPositionMillis?.value ?: 0L) }
        }
    }

    override fun toggleSound() = catch {
        runOnMain {
            val audioController = player?.features?.get(AudioLevelController)
            audioController?.setMute(!(audioController.isMute.value))
        }
    }

    override fun toggleFullscreen() = catch {
        val videoFullScreen = GlobalAppState.toggleVideoFullScreen()
        runBlocking {
            if (videoFullScreen) showTips("[ESC]退出全屏")
        }
    }

    override fun togglePlayStatus() {
        val s = player?.state?.value
        if (s?.isPlaying == true) {
            pause()
        } else {
            play()
        }
    }

    override fun speed(speed: Float) = catch {
        showTips("倍速：$speed")
        runOnMain { player?.features?.get(PlaybackSpeed)?.set(speed) }
    }

    override fun stopForward() {
        isAccelerating = false
        speed(originSpeed)
    }

    override fun fastForward() {
        if (!isAccelerating) {
            currentSpeed = player?.features?.get(PlaybackSpeed)?.value ?: 1.0f
            originSpeed = currentSpeed
            isAccelerating = true
        }
        acceleratePlayback()
    }

    private val maxSpeed = 8.0f

    private fun acceleratePlayback() {
        if (isAccelerating) {
            currentSpeed += 0.5f
            currentSpeed = minOf(currentSpeed, maxSpeed)
            speed(currentSpeed)
            log.debug("Playback rate: {} x", currentSpeed)
        }
    }

    override fun updateEnding(detail: Vod?) {
        _state.update { it.copy(ending = player?.currentPositionMillis?.value ?: -1) }
        history.update { it?.copy(ending = player?.currentPositionMillis?.value ?: -1) }
    }

    override fun updateOpening(detail: Vod?) {
        _state.update { it.copy(opening = player?.currentPositionMillis?.value ?: -1) }
        history.update { it?.copy(opening = player?.currentPositionMillis?.value ?: -1) }
    }

    override fun doWithPlayState(func: (MutableStateFlow<PlayerState>) -> Unit) {
        runBlocking {
            func(_state)
        }
    }

    override fun setStartEnding(opening: Long, ending: Long) {
        _state.update { it.copy(opening = opening, ending = ending) }
    }

    private fun showTips(text: String) {
        runBlocking {
            tip.emit(text)
            showTip.emit(true)
        }
    }
}