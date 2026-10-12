package com.corner.catvodcore.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.corner.bean.HotData
import com.corner.catvod.enum.bean.Site
import com.corner.catvod.enum.bean.Vod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jupnp.UpnpService

object GlobalAppState{
    val showProgress = MutableStateFlow(false)
    var windowState:WindowState? = null
    val hotList = MutableStateFlow(listOf<HotData>())
    val chooseVod = mutableStateOf<Vod>(Vod())
    var detailFrom  = DetailFromPage.HOME
    val home = MutableStateFlow<Site>(Site.get("",""))
    val clear = MutableStateFlow<Boolean>(false)
    val closeApp = MutableStateFlow<Boolean>(false)
    var videoFullScreen = MutableStateFlow<Boolean>(false)
        private set
    var upnpService = mutableStateOf<UpnpService?>(null)
    var DLNAUrl = MutableStateFlow<String>("")

    fun clearHome(){
        home.value = Site.get("","")
    }

    fun toggleVideoFullScreen():Boolean{
        toggleWindowFullScreen()
        videoFullScreen.value = !videoFullScreen.value
        return videoFullScreen.value
    }

    private fun toggleWindowFullScreen(){
        // 不能用 WindowPlacement.Fullscreen：原生全屏会把窗口切到独占模式，
        // MPV 的原生渲染画布（重级/OpenGL 子窗口）在该模式下无法随窗口重新合成，画面会白屏。
        // 之前用 VLCJ 时是软件解码后由 Canvas 自行 drawImage，属于纯 Compose 绘制，不受此影响。
        // 窗口本身已 undecorated=true，用 Maximized 即可得到同样的无边框全屏效果，且不切换原生窗口状态。
        if(windowState?.placement == WindowPlacement.Fullscreen ||
            windowState?.placement == WindowPlacement.Maximized){
            windowState?.placement = WindowPlacement.Floating
        }else{
            windowState?.placement = WindowPlacement.Maximized
        }
    }

    fun showProgress(){
        showProgress.update { true }
    }

    fun hideProgress(){
        showProgress.update { false }
    }

    fun isShowProgress():Boolean{
        return showProgress.value
    }
}

enum class DetailFromPage(){
    SEARCH, DLNA, HOME
}