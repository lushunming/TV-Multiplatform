package com.corner.update

/**
 * 应用自更新相关配置。
 *
 * 发布物仍然托管在 GitHub Releases，但 GitHub 的 release 资产在国内直连下载极慢甚至失败，
 * 因此下载时把 [GITHUB_HOST] 替换为 [CDN_HOST] 走 CDN 加速。
 * GitHub Releases API（检查版本、列举资产）本身走 api.github.com，流量很小，保持直连即可。
 */
object UpdateConfig {
    private const val GITHUB_HOST = "github.com"
    private const val REPO = "lushunming/TV-Multiplatform"

    /**
     * GitHub release 资产下载加速地址，拼接在原始 github.com 下载链接前面。
     * 常见公共加速服务（按需更换）：
     * - https://gh-proxy.com/
     * - https://ghfast.top/
     * - https://mirror.ghproxy.com/
     */
    private const val CDN_HOST = "https://mirror.ghproxy.com/"

    /** 当前应用版本号，与 gradle/libs.versions.toml 中的 app-version 保持一致。 */
    const val CURRENT_VERSION = "1.2.0"

    val owner: String = REPO.substringBefore("/")
    val repo: String = REPO.substringAfter("/")

    /**
     * 把 github.com 的 release 下载链接转换为 CDN 加速链接。
     * 仅替换 github.com 主机部分，其他主机（比如已经是 CDN 的地址）原样返回。
     */
    fun toCdnUrl(url: String): String {
        if (url.isBlank()) return url
        if (!url.contains("://$GITHUB_HOST/") && !url.startsWith(GITHUB_HOST)) return url
        val path = url.substringAfter("://$GITHUB_HOST/")
        return "$CDN_HOST/$GITHUB_HOST/$path"
    }
}
