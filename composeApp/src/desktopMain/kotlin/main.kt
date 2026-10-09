import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cn.hutool.core.util.SystemPropsUtil
import com.corner.RootContent
import com.corner.bean.SettingStore
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.init.Init
import com.corner.init.generateImageLoader
import com.corner.ui.Util
import com.corner.ui.scene.SnackBar
import com.corner.util.SysVerUtil
import com.corner.update.UpdateDialog
import com.seiko.imageloader.LocalImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import org.jetbrains.compose.resources.painterResource
import org.slf4j.LoggerFactory
import tv_multiplatform.composeapp.generated.resources.Res
import tv_multiplatform.composeapp.generated.resources.TV_icon_s
import java.awt.Dimension
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds


private val log = LoggerFactory.getLogger("main")

fun main() {
    val isTraining = System.getProperty("compose.aot.training-run") == "true"
    val isCi = System.getenv("GITHUB_ACTIONS") == "true"

    if (isTraining && isCi) {
        thread(name = "ci-training-watchdog") {
            Thread.sleep(7_800_000)
            println(">>> CI training watchdog: force halt")
            Runtime.getRuntime().halt(0)
        }
    }
    launchErrorCatcher()
    printSystemInfo()
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Performing cleanup before exiting...")
        Init.stop()
    })
//    System.setProperty("java.net.useSystemProxies", "true");
    application {

        val windowState = rememberWindowState(
            size = Util.getPreferWindowSize(600, 500), position = WindowPosition.Aligned(Alignment.Center)
        )
        GlobalAppState.windowState = windowState

        LaunchedEffect(Unit) {
            launch(Dispatchers.Default) {
                Init.start()
            }
        }

        val transparent = rememberUpdatedState(!SysVerUtil.isWin10())
        val scope = rememberCoroutineScope()

        val contextMenuRepresentation =
            if (isSystemInDarkTheme()) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
        Window(
            onCloseRequest = ::exitApplication, icon = painterResource(Res.drawable.TV_icon_s), title = "TV",
            state = windowState,
            undecorated = true,
            transparent = false,
        ) {
            window.minimumSize = Dimension(700, 600)
            CompositionLocalProvider(
                LocalImageLoader provides remember { generateImageLoader() },
                LocalContextMenuRepresentation provides remember { contextMenuRepresentation },
                LocalTextStyle provides LocalTextStyle.current.copy(),
            ) {
                RootContent(modifier = Modifier.fillMaxSize())
                // 应用启动后检查更新，有新版本时弹出更新弹窗（覆盖在整个应用之上）
                UpdateDialog()
            }
            scope.launch {
                GlobalAppState.closeApp.collect {
                    if (it) {
                        try {
                            window.isVisible = false
                            SettingStore.write()
                        } catch (e: Exception) {
                            log.error("关闭应用异常", e)
                        } finally {
                            exitApplication()
                        }
                    }
                }
            }
        }
        // Training run 支持（CI / 打包时用）
        if (System.getProperty("compose.aot.training-run") == "true") {
            LaunchedEffect(Unit) {
                delay(8.seconds)   // 等应用完全启动 + 加载主要类
                // 也可以在这里主动打开几个主要页面，让更多类被加载
                exitApplication()
            }
        }

    }
}

fun printSystemInfo() {
    val s = StringBuilder("\n")
    getSystemPropAndAppend("java.version", s)
    getSystemPropAndAppend("java.home", s)
    getSystemPropAndAppend("os.name", s)
    getSystemPropAndAppend("os.arch", s)
    getSystemPropAndAppend("os.version", s)
    getSystemPropAndAppend("user.dir", s)
    getSystemPropAndAppend("user.home", s)
    log.info("系统信息：{}", s.toString())
}

private fun getSystemPropAndAppend(key: String, s: StringBuilder) {
    val v = SystemPropsUtil.get(key)
    if (v.isNotBlank()) {
        s.append(key).append(":").append(v).append("\n")
    }
}

private fun launchErrorCatcher() {
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        SnackBar.postMsg("未知异常， 请查看日志")
        log.error("未知异常", e)
        Init.stop()
//        Dialog(Frame(), e.message ?: "Error").apply {
//            log.error("启动异常", e)
//            layout = FlowLayout()
//            val label = Label(e.message)
//            val text = TextArea(e.stackTraceToString())
//            add(label)
//            add(text)
//            val button = Button("OK").apply {
//                addActionListener { dispose() }
//            }
//            add(button)
//            setSize(300, 300)
//            isVisible = true
//        }
    }
}