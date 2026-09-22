package com.corner.update

import com.pavi2410.appupdater.AssetDownloader
import com.corner.catvodcore.util.Paths
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 重写官方 [com.pavi2410.appupdater.DesktopAssetDownloader] 的下载逻辑：
 *
 * - 下载地址走 [UpdateConfig.toCdnUrl] 的 CDN 加速，而不是直连 github.com；
 * - 复用项目里配置好的代理（[com.corner.util.KtorClient.getProxy]），开启失败重试；
 * - 下载文件存到应用缓存目录而不是系统临时目录。
 */
class CdnAssetDownloader(
    private val downloadDir: File = File(Paths.userDataRoot(), "update").apply { mkdirs() },
    private val httpClient: HttpClient = createDownloadClient(),
) : AssetDownloader {

    override suspend fun download(
        url: String,
        fileName: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        downloadDir.mkdirs()
        val outputFile = File(downloadDir, fileName)

        // 走 CDN 加速地址下载
        val cdnUrl = UpdateConfig.toCdnUrl(url)
        log.info("开始下载更新包: {} -> {}", url, cdnUrl)

        httpClient.prepareGet(cdnUrl) {
            header("User-Agent", "TV-Multiplatform-Updater")
            onDownload { bytesSentTotal, contentLength ->
                onProgress(bytesSentTotal, contentLength ?: 0L)
            }
        }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            outputFile.outputStream().use { fileOut ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        fileOut.write(buffer, 0, bytesRead)
                    }
                }
            }
        }

        log.info("更新包下载完成: {}", outputFile.absolutePath)
        outputFile.absolutePath
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(CdnAssetDownloader::class.java)

        private fun createDownloadClient(): HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    proxy(com.corner.util.KtorClient.getProxy())
                }
            }
            install(HttpRequestRetry) {
                maxRetries = 2
                delayMillis { 1000L }
            }
        }
    }
}
