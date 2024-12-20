package com.corner.init

import com.corner.catvodcore.util.KtorHeaderUrlFetcher
import com.corner.catvodcore.util.Paths
import com.seiko.imageloader.ImageLoader
import com.seiko.imageloader.intercept.bitmapMemoryCacheConfig
import com.seiko.imageloader.intercept.imageMemoryCacheConfig
import com.seiko.imageloader.intercept.painterMemoryCacheConfig
import okio.Path.Companion.toOkioPath

actual fun initPlatformSpecify() {
}

fun generateImageLoader(): ImageLoader {
    return ImageLoader {
        components {
//            setupDefaultComponents()
            add(KtorHeaderUrlFetcher.CustomUrlFetcher)
        }
        interceptor {
            // cache 32MB bitmap
            bitmapMemoryCacheConfig {
                maxSize(32 * 1024 * 1024) // 32MB
            }
            // cache 50 image
            imageMemoryCacheConfig {
                maxSize(250)
            }
            // cache 50 painter
            painterMemoryCacheConfig {
                maxSize(250)
            }

            diskCacheConfig {
                directory(Paths.picCache().toOkioPath())
                maxSizeBytes(512L * 1024 * 1024) // 512MB
            }
        }
    }
}
