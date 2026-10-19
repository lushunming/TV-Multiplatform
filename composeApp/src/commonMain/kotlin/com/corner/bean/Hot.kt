package com.corner.bean

import SiteViewModel
import com.corner.catvodcore.util.Http
import com.corner.catvodcore.util.Jsons
import com.corner.catvodcore.util.Paths
import com.corner.catvodcore.viewmodel.GlobalAppState
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("Hot")

@Serializable
data class Hot(val data: List<HotData>) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        public fun getHotList() {
            SiteViewModel.viewModelScope.launch {
                // 先展示磁盘缓存，让搜索页热搜、顶栏轮播提示立即有内容，再后台刷新
                val cached = readHotCache()
                if (cached != null && GlobalAppState.hotList.value.isEmpty()) {
                    GlobalAppState.hotList.value = cached
                }
                var response: Response? = null
                try {
                    response = Http.Get(
                        "https://api.web.360kan.com/v1/rank?cat=1",
                        headers = mapOf(HttpHeaders.Referrer to "https://www.360kan.com/rank/general").toHeaders()
                    ).execute()
                } catch (e: Exception) {
                    log.error("请求热搜失败", e)
                }

                if (response?.isSuccessful == true) {
                    val data = Jsons.decodeFromStream<Hot>(response.body.byteStream()).data
                    GlobalAppState.hotList.value = data
                    writeHotCache(data)
                }
            }
        }

        private fun hotCacheFile(): File = File(Paths.hotCache(), "hotlist.json")

        private fun readHotCache(): List<HotData>? = try {
            val f = hotCacheFile()
            if (f.exists() && f.length() > 0) Jsons.decodeFromString<List<HotData>>(f.readText()) else null
        } catch (e: Exception) {
            log.debug("读取热搜缓存失败", e)
            null
        }

        private fun writeHotCache(list: List<HotData>) {
            try {
                hotCacheFile().writeText(Jsons.encodeToString(list))
            } catch (e: Exception) {
                log.debug("写入热搜缓存失败", e)
            }
        }
    }
}

@Serializable
data class HotData(val title: String, val comment: String, val upinfo: String, val description: String)