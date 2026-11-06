package com.corner.ui.nav.vm

import com.corner.bean.SettingStore
import com.corner.database.Db
import com.corner.database.entity.Config
import com.corner.ui.nav.BaseViewModel
import com.corner.ui.nav.data.SettingScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class SettingViewModel: BaseViewModel() {
    private val _state = MutableStateFlow(SettingScreenState())
    val state: StateFlow<SettingScreenState> = _state

    fun sync() {
        _state.update { it.copy(settingList = SettingStore.getSettingList(), version = _state.value.version + 1) }
    }

    fun getConfigAll() {
        scope.launch {
            val flow = Db.Config.getAll().firstOrNull() ?: emptyList()
            _state.update { it.copy(dbConfigList = flow.toMutableList()) }
        }
    }

    fun deleteHistoryById(config: Config) {
        scope.launch {
            Db.Config.deleteById(config.id)
            // 必须生成新的列表实例：原地 remove 后把同一个引用塞回 copy，
            // data class equals 相等，StateFlow 不会发射，下拉框里的配置不会消失
            _state.update { state ->
                state.copy(dbConfigList = state.dbConfigList.filterNot { it.id == config.id }.toMutableList())
            }
        }
    }
}