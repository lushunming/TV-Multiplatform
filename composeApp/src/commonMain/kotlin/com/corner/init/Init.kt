package com.corner.init

import com.corner.bean.Hot
import com.corner.bean.SettingStore
import com.corner.bean.SettingType
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.config.init
import com.corner.catvodcore.enum.ConfigType
import com.corner.catvodcore.loader.JarLoader
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.catvodcore.viewmodel.GlobalAppState.hideProgress
import com.corner.catvodcore.viewmodel.GlobalAppState.showProgress
import com.corner.database.Db
import com.corner.database.appModule
import com.corner.dlna.TVMUpnpService
import com.corner.server.KtorD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Init")
class Init {
    companion object {
        private var instance: KoinApplication? = null
        suspend fun start() {
            showProgress()
            try {
                initKoin()
                coroutineScope {
                    // Netty 服务器与配置加载（同步下载配置 JSON + 爬虫 jar）、热搜请求并行，
                    // 三者互不依赖，串行只会拉长首屏等待。
                    // KtorD 不能完全懒启动：蜘蛛生成的 localhost:9978/proxy 链接只有服务器在运行时才可达
                    launch { KtorD.init() }
                    launch { Hot.getHotList() }
                    launch(Dispatchers.IO) { initConfig() }
                }
                initPlatformSpecify()
            } finally {
                hideProgress()
            }
        }

        fun stop(){
            KtorD.stop()
            instance?.close()
        }


        private fun initKoin() {
            instance = startKoin {
//                logger()
                modules(appModule)
            }
        }
    }
}

expect fun initPlatformSpecify()

suspend fun initConfig() {
    log.info("initConfig start")
    JarLoader.clear()
    ApiConfig.clear()
    GlobalAppState.clear.update {!it}

    val vod = SettingStore.getSettingItem(SettingType.VOD.id)
    if(StringUtils.isBlank(vod)) return
    val siteConfig = withContext(Dispatchers.IO){
        Db.Config.findOneByType(ConfigType.SITE.ordinal.toLong())
    }
    if(siteConfig == null) return
    try {
        ApiConfig.parseConfig(siteConfig, false).init()
    } catch (e: Exception) {
        log.error("initConfig error 尝试使用json解析", e)
        ApiConfig.parseConfig(siteConfig, true).init()
    }
    log.info("initConfig end")
}