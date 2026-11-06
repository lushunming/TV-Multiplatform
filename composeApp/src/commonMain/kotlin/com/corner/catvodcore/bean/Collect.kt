package com.corner.catvodcore.bean

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.corner.catvod.enum.bean.Site
import com.corner.catvod.enum.bean.Vod

data class Collect(
    var activated: MutableState<Boolean> = mutableStateOf(false),
    var list: MutableList<Vod> = mutableListOf(),
    var site: Site? = null,
    var page: Int = 0
){
    companion object {
        /** “全部”聚合项使用的固定站源 key */
        const val ALL_KEY = "all"

        fun all(): Collect {
            val item: Collect = Collect(site = Site.get(ALL_KEY, "全部"),list = mutableListOf())
            item.activated.value = true
            return item
        }

        fun create(list: MutableList<Vod>): Collect {
            return Collect(site = list[0].site!!, list = list)
        }
    }
}