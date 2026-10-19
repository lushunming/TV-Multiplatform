package com.corner.catvodcore.config

import SiteViewModel
import com.corner.catvod.enum.bean.Rule
import com.corner.catvod.enum.bean.Site
import com.corner.catvodcore.bean.Api
import com.corner.catvodcore.enum.ConfigType
import com.corner.catvodcore.loader.JarLoader
import com.corner.catvodcore.util.Http
import com.corner.catvodcore.util.Jsons
import com.corner.catvodcore.util.Paths as AppPaths
import com.corner.catvodcore.util.Urls
import com.corner.catvodcore.util.Utils
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.Db
import com.corner.database.entity.Config
import com.corner.ui.scene.SnackBar
import com.corner.util.createCoroutineScope
import com.corner.util.isEmpty
import com.github.catvod.crawler.Spider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Headers
import okio.Path.Companion.toPath
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("apiConfig")

object ApiConfig {
    private val scope = createCoroutineScope()
    var apiFlow = MutableStateFlow(Api(spider = ""))
    var api: Api = apiFlow.value

    init {
        collectApi()
    }

    private fun collectApi() {
        scope.launch {
            apiFlow.collect { api = it }
        }
    }

    fun clear() {
        apiFlow.value = Api(spider = "")
    }

    fun parseConfig(cfg: Config, isJson: Boolean): Api {
        log.info("parseConfig start cfg:{} isJson:{}", cfg, isJson)
        val data = getData(if (isJson) cfg.json ?: "" else cfg.url!!, isJson) ?: throw RuntimeException("配置读取异常")
        if (StringUtils.isBlank(data)) {
            log.warn("配置数据为空")
            SnackBar.postMsg("配置数据为空 请检查")
            setHome(null)
            return api
        }
        val apiConfig = Jsons.decodeFromString<Api>(data)
        apiFlow.update { apiConfig }
        apiFlow.update { ap -> ap.copy(url = cfg.url, data = data, cfg = cfg, ref = ap.ref + 1) }
        // 同步更新 api 字段，避免 JarLoader 读取到旧的(空的) url 导致无限递归
        api = apiFlow.value
        JarLoader.loadJar("", apiConfig.spider)
        if (cfg.home?.isNotBlank() == true) {
            setHome(api.sites.find { it.key == cfg.home })
        } else {
            setHome(api.sites.first())
        }
        scope.launch {
            api.sites = Db.Site.update(cfg, api)
        }
        log.info("parseConfig end")
        return apiConfig
    }

    fun setHome(home: Site?) {
        GlobalAppState.home.value = home ?: Site.get("", "")
    }

    fun getSpider(site: Site): Spider {
//    val js: Boolean = site.api.contains(".js")
//    val py: Boolean = site.api.contains(".py")
        val csp: Boolean = site.api.startsWith("csp_")
        return/* if (py) pyLoader.getSpider(
        site.key,
        site.api,
        site.ext
    ) else if (js) jsLoader.getSpider(
        site.key,
        site.api,
        site.ext,
        site.jar
    ) else*/ if (csp) JarLoader.getSpider(site.key, site.api, site.ext, site.jar ?: "") else Spider()
    }

    fun getSite(key: String): Site? {
        return api.sites.find { it.key == key }
    }

    fun setRecent(site: Site) {
        api.recent = site.key
        val js: Boolean = site.api.contains(".js")
        val py: Boolean = site.api.startsWith("py_")
        val csp: Boolean = site.api.startsWith("csp_")
        /*if (js) jsLoader.setRecent(site.getKey())
        else if (py) pyLoader.setRecent(site.getKey())
        else*/ if (csp) JarLoader.SetRecent(
            site.jar
        )
    }

    fun parseExt(ext: String): String {
        if (StringUtils.isBlank(ext)) return ext
        if (ext.startsWith("file")) {
            return Files.readString(Paths.get(Urls.convert(ext)))
        }
        if (ext.endsWith(".js") || ext.endsWith(".json") || ext.endsWith(".txt")) {
            return Urls.convert(
                api.url ?: "",
                ext
            )
        }
        return ext
    }

    fun parseApi(str: String): String {
        if (StringUtils.isBlank(str)) return ""
        if (str.startsWith("http") || str.startsWith("file")) return str
        if (str.endsWith(".js")) return parseApi(Urls.convert(api.url ?: "", str))
        return str
    }


    fun initProxy() {
        Http.setProxyHosts(getRuleByName("proxy")?.hosts)
    }

    fun getRuleByName(name: String): Rule? {
        return api.rules.find { i -> i.name == name }
    }

    private fun getData(str: String, isJson: Boolean): String? {
        try {
            if (StringUtils.isBlank(str)) {
                return ""
            }
            if (isJson) {
                return Jsons.decodeFromString(str)
            } else if (str.startsWith("http")) {
                return fetchConfigWithCache(str)
            } else if (str.startsWith("file")) {
                val file = Urls.convert(str).toPath().toFile()
                if (!file.exists()) {
                    return ""
                }
                return Files.readString(file.toPath())
            }
        } catch (e: Exception) {
            SnackBar.postMsg("获取配置失败: " + e.message)
            log.error("获取配置失败", e)
            return ""
        }
        return ""
    }

    private data class CacheMeta(val etag: String?, val lastModified: String?)

    /**
     * 下载配置 JSON 并做磁盘缓存 + 条件请求（ETag / Last-Modified）：
     * - 服务端返回 304 时复用本地缓存，避免每次启动重新下载整份配置（可能数百 KB）
     * - 下载失败（断网、站点抖动）时回退到上次成功的缓存，保证启动后仍有站点可用
     */
    private fun fetchConfigWithCache(url: String): String {
        val key = Utils.md5(url)
        val cacheFile = File(AppPaths.configCache(), "$key.json")
        val metaFile = File(AppPaths.configCache(), "$key.meta")
        val cached = readTextQuietly(cacheFile)
        val meta = readMeta(metaFile)

        val headerBuilder = Headers.Builder()
        if (cached != null) {
            meta.etag?.let { headerBuilder.add("If-None-Match", it) }
            meta.lastModified?.let { headerBuilder.add("If-Modified-Since", it) }
        }

        try {
            Http.Get(url, null, headerBuilder.build()).execute().use { resp ->
                if (resp.code == 304 && cached != null) {
                    log.info("配置未变更(304)，使用本地缓存")
                    return cached
                }
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    if (body.isNotBlank()) {
                        cacheFile.writeText(body)
                        writeMeta(metaFile, resp.header("ETag"), resp.header("Last-Modified"))
                        return body
                    }
                }
                log.warn("配置下载异常 code={}，尝试使用缓存", resp.code)
            }
        } catch (e: Exception) {
            log.error("配置下载失败，尝试使用缓存", e)
        }

        if (cached != null) {
            log.warn("使用本地缓存的配置: {}", cacheFile.path)
            return cached
        }
        throw RuntimeException("配置下载失败且无本地缓存")
    }

    private fun readMeta(file: File): CacheMeta {
        return try {
            if (!file.exists()) return CacheMeta(null, null)
            val lines = file.readLines()
            CacheMeta(
                lines.getOrNull(0)?.takeIf { it.isNotBlank() },
                lines.getOrNull(1)?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            CacheMeta(null, null)
        }
    }

    private fun writeMeta(file: File, etag: String?, lastModified: String?) {
        try {
            file.writeText("${etag.orEmpty()}\n${lastModified.orEmpty()}")
        } catch (e: Exception) {
            log.debug("写入配置缓存元信息失败", e)
        }
    }

    private fun readTextQuietly(file: File): String? {
        return try {
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }

}

fun Api.init() {
    ApiConfig.initProxy()
    initSite()
}

fun Api.initSite() {
    if (sites.isEmpty()) return
    for (site in sites) {
        site.api = ApiConfig.parseApi(site.api)
        site.ext = ApiConfig.parseExt(site.ext)
    }
    if (GlobalAppState.home.value.isEmpty() && sites.size > 0) {
        GlobalAppState.home.value = sites.first()
        SiteViewModel.viewModelScope.launch {
            Db.Config.setHome(url, ConfigType.SITE.ordinal, GlobalAppState.home.value.toString())
        }
    }
}