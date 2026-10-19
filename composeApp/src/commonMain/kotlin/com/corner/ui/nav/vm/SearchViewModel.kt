package com.corner.ui.nav.vm

import SiteViewModel
import com.corner.bean.SettingStore
import com.corner.catvodcore.bean.Collect
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.ui.nav.BaseViewModel
import com.corner.ui.nav.data.SearchScreenState
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList


class SearchViewModel: BaseViewModel() {
    private val _state = MutableStateFlow(SearchScreenState())
    val state:StateFlow<SearchScreenState> = _state

    private var jobList: MutableList<Job> = mutableListOf<Job>()
    private var refreshListJob: Job? = null

    private val supervisorJob = SupervisorJob()
    private val searchScope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(8) + supervisorJob)
    
    fun onSearch(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
    }

    fun onCreate() {
        log.debug("onCreate called")
        _state.value.searchableSites.addAll(ApiConfig.api.sites.filter {
            it.searchable == 1 && !_state.value.searchCompleteSites.contains(
                it.key
            )
        }.shuffled().map { it.copy() })
        _state.update { it.copy(hotList = GlobalAppState.hotList.value, historyList = SettingStore.getHistoryList()) }

        scope.launch {
            _state.collect() {
                _state.update { it.copy(searchBarText = getSearchBarText(_state.value)) }
            }
        }
    }

    fun onPause() {
        log.debug("search onPause")
        supervisorJob.cancelChildren(CancellationException("onDestroy"))
    }

    private fun getSearchBarText(model: SearchScreenState): String {
        val size = _state.value.searchableSites.filter { it.isSearchable() }.size
        if (_state.value.isSearching) return "${_state.value.searchCompleteSites.size}/$size"
        return "$size"
    }

    /**
     * 当用户从搜索页面进入详情页， 而后返回搜索页 因为 DisposableEffect 会再次执行
     * 这个搜索方法，为避免不必要的搜索操作， searchedText记录上上次执行搜索的文本，如果一样就不再执行搜索，
     * isLoadMore用来指示搜索操作的来源，一个是 DisposableEffect， 另一个是加载更多按钮
     */
    fun search(searchText: String, isLoadMore: Boolean) {
        log.info("开始搜索：{}", searchText)
        if (_state.value.searchedText == searchText && !isLoadMore) {
            log.debug("已经搜索过：{}，忽略", searchText)
            return
        }
        if (_state.value.searchCompleteSites.size == ApiConfig.api.sites.size) {
            log.info("所有站源已经搜索完毕")
            return
        }
        _state.update { it.copy(searchedText = searchText) }
        if (!isLoadMore) {
            clear()
        }
        _state.update { it.copy(isSearching = true) }
        SiteViewModel.viewModelScope.launch {
            SettingStore.addSearchHistory(searchText)
            var searchableSites =
                _state.value.searchableSites.filter { it.searchable == 1 && !_state.value.searchCompleteSites.contains(it.key) }
                    .shuffled()
            searchableSites = searchableSites.subList(0, _state.value.onceSearchSiteNum.coerceAtMost(searchableSites.size))
            log.info("站源：{}", searchableSites.map { it.name })
            searchableSites.forEach { site ->
                val job = searchScope.launch {
                    _state.value.searchCompleteSites.add(site.key)
                    _state.update { it.copy(searchCompleteSites = it.searchCompleteSites) }
                    SiteViewModel.searchContent(site, searchText, false)
                }
                job.invokeOnCompletion { e ->
                    if (e != null) {
                        if (e is CancellationException) {
                            log.debug("搜索被取消 msg:${e.message}")
                        } else {
                            log.error("搜索执行 异常 msg:{}", e.message)
                        }
                    }
                    // 合并多个站源的完成事件，延迟批量刷新，避免每个站源完成都全量重建列表、整网格重组
                    scheduleRefreshCurrentVodList()
                    if (e == null) log.debug("一个job执行完毕 List size:{}", _state.value.currentVodList.value.size)
                }
                jobList.add(job)
            }
            searchScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }.invokeOnCompletion {
            _state.update { it.copy(isSearching = false) }
        }
    }

    private fun cancelJobAndClear() {
        supervisorJob.cancelChildren(CancellationException("clear"))
        jobList.forEach { i -> i.cancel("search clear") }
        jobList.clear()
        refreshListJob?.cancel()
    }

    /**
     * 多个站源并发搜索时几乎同时完成，逐个刷新会让结果网格全量重组多次。
     * 把 300ms 内的完成事件合并成一次刷新。
     */
    private fun scheduleRefreshCurrentVodList() {
        refreshListJob?.cancel()
        refreshListJob = searchScope.launch {
            delay(300)
            refreshCurrentVodList()
        }
    }

    fun refreshCurrentVodList() {
        val activeList = SiteViewModel.search.value.find { it.activated.value }?.list ?: listOf()
        // 内容没有变化就不重建列表（例如正在查看某个站源时其他站源完成）
        if (activeList != _state.value.currentVodList.value) {
            _state.value.currentVodList.value = activeList.toMutableList()
        }
    }

    fun clear() {
        log.info("清空工作区")
        cancelJobAndClear()
        SiteViewModel.clearSearch()
        _state.value.searchCompleteSites.clear()
        _state.value.currentVodList.value = CopyOnWriteArrayList()
        _state.update {
            it.copy(
                isSearching = false
            )
        }
    }

    fun onClickCollection(item: Collect) {
        refreshListJob?.cancel() // 用户手动切换站源，立即生效，丢弃待执行的合并刷新
        _state.value.currentVodList.value = CopyOnWriteArrayList(item.list)
    }

    fun updateModel(function: (SearchScreenState) -> Unit) {
        function(_state.value)
        val searchBarText = getSearchBarText(_state.value)
        _state.update { it.copy(ref = it.ref + 1, searchBarText = searchBarText) }
    }

}