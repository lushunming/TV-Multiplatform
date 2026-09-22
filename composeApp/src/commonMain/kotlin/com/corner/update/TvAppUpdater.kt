package com.corner.update

import com.corner.update.UpdateConfig.CURRENT_VERSION
import com.corner.update.UpdateConfig.owner
import com.corner.update.UpdateConfig.repo
import com.pavi2410.appupdater.AppUpdater
import com.pavi2410.appupdater.DesktopAssetInstaller
import com.pavi2410.appupdater.github

/**
 * 全局唯一的 [AppUpdater] 实例。
 *
 * - 使用官方的 `AppUpdater.github(...)` 创建，版本检查走 GitHub Releases API；
 * - 下载器替换为 [CdnAssetDownloader]，安装包从 CDN 加速地址下载；
 * - 安装器使用官方 [DesktopAssetInstaller]，由系统拉起安装。
 */
object TvAppUpdater {

    val updater: AppUpdater by lazy {
        AppUpdater.github(
            owner = owner,
            repo = repo,
            currentVersion = CURRENT_VERSION,
            assetMatcher = { name ->
                // 匹配当前系统对应的安装包，避免选中其他平台的包
                val osName = System.getProperty("os.name").lowercase()
                when {
                    osName.contains("win") -> name.endsWith(".msi") || name.endsWith(".exe")
                    osName.contains("mac") -> name.endsWith(".dmg")
                    osName.contains("nux") || osName.contains("nix") ->
                        name.endsWith(".deb") || name.endsWith(".rpm") || name.endsWith(".AppImage")
                    else -> name.endsWith(".msi") || name.endsWith(".dmg") || name.endsWith(".deb")
                }
            },
        ).copyWithCdnDownloader()
    }

    private fun AppUpdater.copyWithCdnDownloader(): AppUpdater = AppUpdater(
        currentVersion = currentVersion,
        source = source,
        downloader = CdnAssetDownloader(),
        installer = DesktopAssetInstaller(),
        assetMatcher = assetMatcher,
    )
}
