/*import org.jetbrains.compose.desktop.application.dsl.AotMode*/
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
//    id("java")
    id("dev.hydraulic.conveyor") version "2.0"
}

room {
    schemaDirectory("$projectDir/src/commonMain/schemas")
}

dependencies {
//    implementation("io.ktor:ktor-server-cors:3.1.2")
    ksp(libs.roomCompiler)
    linuxAmd64(compose.desktop.linux_x64)
    macAmd64(compose.desktop.macos_x64)
    macAarch64(compose.desktop.macos_arm64)
    windowsAmd64(compose.desktop.windows_x64)
}


kotlin {
    jvm("desktop")
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)

    }
    // mediamp-all 的 Android 变体会把 kotlinx-coroutines-android 拉进桌面运行时类路径，
    // 它的 AndroidDispatcherFactory (SPI 优先级最高) 会让 Dispatchers.Main 尝试加载
    // android.os.Looper，在桌面 JVM 上必然失败。排除它，让 SwingDispatcherFactory 生效。
    configurations.all {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    }
    compilerOptions {
        // kmp-app-updater 的 core 模块使用了 expect/actual class，需要该编译参数
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    /**
     * 返回当前构建机对应的 mediamp-mpv-runtime 坐标。
     * 与 MediaMP LibraryLoader.platformSuffixFor 规则一致：
     *   windows/macos/linux + x64/arm64
     */
    fun mediampMpvRuntimeCoordinate(version: String): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()

        val os = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "macos"
            else -> "linux"
        }
        val arch = when (osArch) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64" // amd64, x86_64, …
        }

        // Linux 目前官方只有 x64；arm64 没有 artifact 时会解析失败，可按需改成 error
        if (os == "linux" && arch == "arm64") {
            error("mediamp-mpv-runtime-linux-arm64 is not published; use linux-x64 host or manage natives yourself.")
        }

        return "org.openani.mediamp:mediamp-mpv-runtime-$os-$arch:$version"
    }
    sourceSets {
        commonMain {
            dependencies {
                val ktorVer = "3.1.2"
                val logbackVer = "1.3.14"
                val hutoolVer = "5.8.27"
//            val kotlinVersion = extra["kotlin.version"] as String
//            implementation(project(":CatVod"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)

//            implementation(libs.roomCompiler)
                // room database access
                implementation(libs.roomRuntime)
                implementation(libs.roomGuava)
                implementation(libs.roomKtx)
                implementation(libs.roomBundled)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.navigation.compose)

                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
//            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")

                // tool
                api("com.google.guava:guava:31.1-jre")
                implementation("cn.hutool:hutool-all:$hutoolVer")


                //DI
                api("io.insert-koin:koin-core:3.5.3")
                api("io.insert-koin:koin-test:3.5.3")

                // spider depend on
                api("org.json:json:20231013")
                implementation("com.google.code.gson:gson:2.10.1")
                implementation("com.github.lookfirst:sardine:5.10")
                implementation("cn.wanghaomiao:JsoupXpath:2.5.1")
                implementation("org.jsoup:jsoup:1.15.3")
                implementation("com.google.zxing:core:3.3.0")
                implementation("org.nanohttpd:nanohttpd:2.3.1")

                //ktor http server
                implementation("io.ktor:ktor-server-core:$ktorVer")
                implementation("io.ktor:ktor-server-netty:$ktorVer")
//                implementation("io.ktor:ktor-server-status-pages:$ktorVer")
                implementation("io.ktor:ktor-server-cors:$ktorVer")
                implementation("io.ktor:ktor-server-default-headers:$ktorVer")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVer")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVer")
                implementation("io.ktor:ktor-server-swagger:$ktorVer")
                implementation("io.ktor:ktor-client-core:$ktorVer")
                implementation("io.ktor:ktor-client-okhttp:$ktorVer")

                // log
                implementation("ch.qos.logback:logback-classic:$logbackVer")
                // image-loader
                api(libs.image.loader)

                // in-app updater (https://github.com/pavi2410/kmp-app-updater)
                implementation(libs.app.updater.core)
                implementation(libs.app.updater.compose.ui)
                // optional - Moko Resources Decoder
//                api("io.github.qdsfdhvh:image-loader-extension-moko-resources:$imageLoader")

                api(project.dependencies.platform("com.squareup.okhttp3:okhttp-bom:5.0.0-alpha.14"))
                api("com.squareup.okhttp3:okhttp")
                api("com.squareup.okhttp3:okhttp-dnsoverhttps")


                // DLNA
                implementation(libs.jupnp.bom.compile)
                implementation(libs.jupnp.support)
                implementation(libs.jupnp.osgi)
                implementation("org.apache.logging.log4j:log4j-api:2.20.0")

                // Player - mediamp (MPV backend for desktop)
                api(libs.mediamp.all)
//            implementation(libs.jupnp)
//                implementation(libs.jetty.servlet)
//                implementation(libs.jetty.server)
//                implementation(libs.jetty.client)
//                implementation(project(":Upnp"))

//            api("com.arkivanov.decompose:decompose:3.3.0")
//            api("com.arkivanov.decompose:extensions-compose:3.3.0")
//            // Add the dependency, typically under the commonMain source set
//            api("com.arkivanov.essenty:lifecycle:2.5.0")

                implementation("com.github.luben:zstd-jni:1.5.7-4")

            }

        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // mediamp (MPV) 状态机需要 Dispatchers.Main (Swing EDT)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // MPV native runtime (all platforms), brings mpv-natives-*.txt and libraries into classpath
            // 按本机 OS + CPU 自动选择 mediamp-mpv-runtime
                val mediampVersion = libs.versions.mediamp.get() // 或写死 "0.5.1"
                runtimeOnly(mediampMpvRuntimeCoordinate(mediampVersion))
                /* // MPV external player service (JSON IPC)
                 // https://github.com/kknifer7/MpvService
                 implementation("io.github.kknifer7:mpv:0.1.4")*/
                // desktopMain / jvmMain 里
                /* implementation("net.java.dev.jna:jna:5.16.0")
                 implementation("net.java.dev.jna:jna-platform:5.16.0")

                 // 如果有其他地方声明了 jpms，强制排除
                 configurations.all {
                     exclude(group = "net.java.dev.jna", module = "jna-jpms")
                     exclude(group = "net.java.dev.jna", module = "jna-platform-jpms")
                 }*/

            }
        }



//        desktopMain.dependencies {
//            implementation(compose.desktop.currentOs)
//            // Player
//            implementation(libs.vlcj)
//        }
    }
}


compose.desktop {
    application {
        mainClass = "MainKt"

        buildTypes.release.proguard {
//            obfuscate.set(true)
            isEnabled.set(true)
            version.set("7.9.0")
            configurationFiles.from(project.file("src/desktopMain/rules.pro"))
        }

        jvmArgs("-Dfile.encoding=UTF-8 -Dsun.net.http.allowRestrictedHeaders=true")

     /* buildTypes.release.aot {
            // 推荐：使用 JDK 25 的 AOT（效果最好）
          //  mode = AotMode.AotPrebuild

            // 或者只用 AppCDS（兼容稍低 JDK）：
             mode = AotMode.AppCdsAuto   // 需要 JDK 21+
            // mode = AotMode.AppCdsAuto       // 首次运行时自动生成，需要 JDK 19+

            // logging = true   // 可选，调试用
        }*/

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb
            )
            packageName = "TVMultiplatform"
            packageVersion = libs.versions.app.version.get()
            vendor = "TV Multiplatform"

            modules(
                "java.compiler",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.security.jgss",
                "java.sql",
                "jdk.httpserver",
                "jdk.unsupported",
                "java.desktop"
            )
            val dir = project.layout.projectDirectory.dir("src/desktopMain/appResources")
            println(dir)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/desktopMain/appResources"))
//            app icons https://github.com/JetBrains/compose-multiplatform/tree/master/tutorials/Native_distributions_and_local_execution#app-icon
            windows {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/icon-s.ico"))
                dirChooser = true
                upgradeUuid = "161FA5A0-A30B-4568-9E84-B3CD637CC8FE"
                menu = true
               // menuGroup = "video"
               // console = true
                shortcut=true
            }

            linux {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/TV-icon-s.png"))
            }

            macOS {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/icon.icns"))
            }

        }

    }
}
