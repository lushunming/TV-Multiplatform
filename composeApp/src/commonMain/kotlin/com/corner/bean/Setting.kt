package com.corner.bean

import ch.qos.logback.classic.Level
import com.corner.bean.enums.PlayerType
import com.corner.catvodcore.util.Jsons
import com.corner.catvodcore.util.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.exists

@Serializable
data class Setting(val id: String, val label: String, var value: String = "")

/**
 * 以井号分割的字符串
 * #
 */
fun Setting.parseValueToList():List<String>{
    return value?.split("#") ?: listOf()
}

@Serializable
sealed interface Cache{
    fun getName():String

    fun add(t:String)
}

@Serializable
class SearchHistoryCache:Cache{

    private val maxSize:Int = 30

    private var searchHistoryList:LinkedHashSet<String> = linkedSetOf()
    override fun getName(): String {
        return "searchHistory"
    }

    override fun add(t:String) {
        if(searchHistoryList.size >= maxSize){
            val list:LinkedHashSet<String> = linkedSetOf()
            list.addAll(searchHistoryList.drop(1))
            searchHistoryList = list
        }
        searchHistoryList.remove(t)
        searchHistoryList.add(t)
    }

    fun getSearchList():List<String>{
        return searchHistoryList.toList().reversed()
    }

}

@Serializable
class PlayerStateCache:Cache{
    private val map:MutableMap<String, String> = mutableMapOf();
    override fun getName(): String {
        return "playerState"
    }

    override fun add(t: String) {

    }

    fun add(key:String, value: String){
        map.put(key,value)
    }

    fun get(key: String):String?{
        return map.get(key)
    }

}

@Serializable
data class SettingFile(val list: MutableList<Setting>, val cache: MutableMap<String, Cache>)

enum class EditType {
    INPUT,
    CHOOSE
}

enum class SettingType(val id: String) {
    PLAYER("player"),
    VOD("vod"),
    LOG("log"),
    SEARCHHISTORY("searchHistory"),
    PROXY("proxy"),
}

object SettingStore {
    private const val WRITE_DEBOUNCE_MS = 300L

    private val defaultList = listOf(
        Setting("vod", "点播", ""),
        Setting("log", "日志级别", Level.INFO.levelStr),
        Setting("player", "播放器", "false#"),
        Setting("proxy", "代理", "false#")
    )

    private val log = LoggerFactory.getLogger("SettingStore")

    /** 读写保护：UI 线程与播放器协程会并发读写同一份设置 */
    private val lock = Any()

    /** 设置文件写入专用单线程，避免串行写文件时阻塞调用方（尤其是 UI 逐键修改） */
    private val writeScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "SettingStore-writer").apply { isDaemon = true }
        }.asCoroutineDispatcher() + SupervisorJob()
    )

    private var pendingWrite: Job? = null

    @Volatile
    private var settingFile = SettingFile(mutableListOf<Setting>(), mutableMapOf())

    init {
        getSettingList()
    }

    fun getSettingItem(s: String): String {
        return synchronized(lock) { settingFile.list.find { it.id == s }?.value ?: "" }
    }

    fun getSettingItem(type: SettingType):String{
        return getSettingItem(type.id)
    }

    fun getSettingList(): MutableList<Setting> {
        synchronized(lock) {
            if (settingFile.list.isEmpty()) {
                initSetting()
            }
            return settingFile.list
        }
    }

    fun reset(){
        synchronized(lock) {
            settingFile = SettingFile(mutableListOf(), mutableMapOf())
            initSetting()
        }
        write()
    }

    /**
     * 立即落盘。仅在退出、切页等必须保证持久化的时机调用；
     * 高频修改请走 [setValue]/[doWithCache]，它们会合并为一次异步写。
     */
    fun write() {
        val content = synchronized(lock) {
            pendingWrite?.cancel()
            pendingWrite = null
            Jsons.encodeToString(settingFile)
        }
        persist(content)
    }

    fun setValue(type: SettingType, s: String) {
        synchronized(lock) {
            settingFile.list.find { i -> i.id == type.id }?.value = s
        }
        scheduleWrite()
    }

    fun doWithCache(func:(MutableMap<String, Cache>) -> Unit){
        synchronized(lock) { func(settingFile.cache) }
        scheduleWrite()
    }

    fun getCache(name:String): Cache? {
        return synchronized(lock) { settingFile.cache[name] }
    }

    /** 防抖：连续修改（如逐键输入 URL）只在停顿 300ms 后写一次 */
    private fun scheduleWrite() {
        synchronized(lock) {
            pendingWrite?.cancel()
            pendingWrite = writeScope.launch {
                delay(WRITE_DEBOUNCE_MS)
                persist(synchronized(lock) { Jsons.encodeToString(settingFile) })
            }
        }
    }

    private fun persist(content: String) {
        try {
            Files.write(Paths.setting(), content.toByteArray())
        } catch (e: Exception) {
            log.warn("写入设置失败", e)
        }
    }

    private fun initSetting() {
        val file = Paths.setting()
        if (file.exists() && settingFile.list.size == 0) {
            settingFile = Jsons.decodeFromString<SettingFile>(Files.readString(file))
            if (settingFile.list.size != defaultList.size) {
                defaultList.forEach { setting ->
                    if (settingFile.list.find { setting.id == it.id } == null) {
                        settingFile.list.add(setting)
                    }
                }
            }
        }
        if (settingFile.list.size == 0) {
            settingFile.list.addAll(defaultList)
            Files.write(file, Jsons.encodeToString(settingFile).toByteArray())
        }
    }

    fun getHistoryList(): Set<String> {
        synchronized(lock) {
            if (settingFile.list.isEmpty()) {
                initSetting()
            }
            val cache = settingFile.cache[SettingType.SEARCHHISTORY.id]
            if (cache != null) {
                return (cache as SearchHistoryCache).getSearchList().toSet()
            }
            return setOf()
        }
    }

    fun addSearchHistory(s: String){
        if (s.trim().isBlank()) return
        synchronized(lock) {
            val cache = settingFile.cache[SettingType.SEARCHHISTORY.id]
                ?: SearchHistoryCache().also { settingFile.cache[SettingType.SEARCHHISTORY.id] = it }
            cache.add(s)
        }
        scheduleWrite()
    }
}

data class SettingEnable(
    val isEnabled: Boolean,
    val value: String
){
    companion object{
        fun Default():SettingEnable{
            return SettingEnable(false, "")
        }
    }
}

/**
 * boolean#字符串 转换为数组
 */
fun String.parseAsSettingEnable():SettingEnable{
    val split = this.split("#")
    return if(split.size == 1){
        SettingEnable(split.first().toBoolean(), "")
    }else{
        SettingEnable(split.first().toBoolean(), split.last())
    }
}



fun String.getPlayerSetting(sitePlayerType: String? = ""): List<String>{
    val internalPlayer = this.split("#")
        // first is site, second app setting
        val type = if (StringUtils.isNotBlank(
                sitePlayerType
            )
        ) PlayerType.getById(sitePlayerType ?: "").id else internalPlayer.first()
        return listOf(type, internalPlayer[1])
}
