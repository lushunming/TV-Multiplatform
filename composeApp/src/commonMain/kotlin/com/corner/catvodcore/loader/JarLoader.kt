package com.corner.catvodcore.loader

import com.corner.catvodcore.Constant
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.util.Http
import com.corner.catvodcore.util.Paths
import com.corner.catvodcore.util.Urls
import com.corner.catvodcore.util.Utils
import com.github.catvod.crawler.Spider
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

object JarLoader {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val loaders: ConcurrentHashMap<String, URLClassLoader> by lazy { ConcurrentHashMap() }

    // ========== 反射缓存 ==========
    // key = jaKey
    private val proxyHandles: ConcurrentHashMap<String, MethodHandle> by lazy { ConcurrentHashMap() }
    private val initHandles: ConcurrentHashMap<String, MethodHandle> by lazy { ConcurrentHashMap() }

    // key = spKey (jaKey + spiderKey)
    private val spiderConstructors: ConcurrentHashMap<String, Constructor<out Spider>> by lazy { ConcurrentHashMap() }
    private val spiders: ConcurrentHashMap<String, Spider> by lazy { ConcurrentHashMap() }

    var recent: String? = null

    fun clear() {
        loaders.clear()
        proxyHandles.clear()
        initHandles.clear()
        spiderConstructors.clear()
        spiders.clear()
        recent = null
    }

    fun loadJar(key: String, spider: String) {
        if (StringUtils.isBlank(spider)) return
        val texts = spider.split(Constant.md5Split)
        val md5 = if (texts.size <= 1) "" else texts[1].trim()
        val jar = texts[0]

        if (md5.isNotEmpty() && Utils.equals(parseJarUrl(jar), md5)) {
            load(key, Paths.jar(parseJarUrl(jar)))
        } else if (jar.startsWith("file")) {
            load(key, Paths.local(jar))
        } else if (jar.startsWith("http")) {
            load(key, download(jar))
        } else {
            val absJar = Urls.convert(ApiConfig.api.url ?: "", jar)
            if (absJar.isBlank() || absJar == jar) {
                log.warn("无法解析jar相对路径: {} baseUrl: {}", jar, ApiConfig.api.url)
                return
            }
            loadJar(key, absJar)
        }
    }

    private fun parseJarUrl(jar: String): String {
        if (jar.startsWith("file") || jar.startsWith("http")) return jar
        return Urls.convert(ApiConfig.api.url ?: "", jar)
    }

    private fun load(key: String, jar: File) {
        log.debug("load jar {}", jar)
        // 避免重复加载同一个 key
        if (loaders.containsKey(key)) return

        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), this.javaClass.classLoader)
        loaders[key] = loader

        putProxy(key, loader)
        invokeInit(key, loader)
    }

    private fun putProxy(key: String, loader: URLClassLoader) {
        try {
            val clazz = loader.loadClass(Constant.catVodProxy)
            val lookup = MethodHandles.lookup()
            // MethodHandle 创建时完成访问检查
            val handle = lookup.findStatic(
                clazz,
                "proxy",
                MethodType.methodType(Array<Any>::class.java, Map::class.java)
            )
            proxyHandles[key] = handle
        } catch (e: Exception) {
            log.debug("putProxy failed for key=$key", e)
        }
    }

    private fun invokeInit(key: String, loader: URLClassLoader) {
        try {
            val clazz = loader.loadClass(Constant.catVodInit)
            val lookup = MethodHandles.lookup()
            val handle = lookup.findStatic(
                clazz,
                "init",
                MethodType.methodType(Void.TYPE)
            )
            // 只调用一次
            handle.invokeExact()
            initHandles[key] = handle   // 如果以后还需要可以复用
        } catch (e: Exception) {
            log.debug("invokeInit failed for key=$key", e)
        }
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        try {
            val jaKey = Utils.md5(jar)
            val spKey = jaKey + key

            // 1. 已缓存的 Spider 实例直接返回
            spiders[spKey]?.let { return it }

            // 2. 确保 ClassLoader 已加载
            if (loaders[jaKey] == null) {
                loadJar(jaKey, jar)
            }
            val loader = loaders[jaKey] ?: return Spider()

            // 3. 缓存 Constructor（最热路径）
            val constructor = spiderConstructors.computeIfAbsent(spKey) {
                val classPath = "${Constant.catVodSpider}.${api.replace("csp_", "")}"
                val clazz = loader.loadClass(classPath)
                val ctor = clazz.getDeclaredConstructor() as Constructor<out Spider>
                ctor.isAccessible = true          // 只设置一次
                ctor
            }

            val spider = constructor.newInstance() as Spider
            spider.init(ext)
            spiders[spKey] = spider
            return spider
        } catch (e: Exception) {
            log.error("getSpider failed key=$key api=$api", e)
            return Spider()
        }
    }

    private fun download(jar: String): File {
        val jarPath = Paths.jar(jar)
        log.debug("download jar file {} to:{}", jar, jarPath)
        return Paths.write(jarPath, Http.Get(jar).execute().body.bytes())
    }

    fun proxyInvoke(params: Map<String, String>): Array<Any>? {
        return try {
            val md5 = Utils.md5(recent ?: "")
            val handle = proxyHandles[md5] ?: return null
            // invokeExact 性能最好（无装箱、类型精确匹配）
            handle.invokeExact(params) as Array<Any>
        } catch (e: Exception) {
            log.error("proxyInvoke failed", e)
            null
        }
    }

    fun SetRecent(jar: String?) {
        recent = jar
    }
}