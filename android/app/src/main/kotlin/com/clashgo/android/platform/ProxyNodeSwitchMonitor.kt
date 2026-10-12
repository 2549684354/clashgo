package com.clashgo.android.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// 进程级「代理节点已切换」事件：代理页的节点选择经内核确认后发出，首页据此把三个
// 站点的访问延迟重测一次（节点换了，实际访问延迟随之改变）。事件不携带数据，
// 错过一次也只是少测一轮，手动刷新仍可补测。
class ProxyNodeSwitchMonitor {

    private val _switches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val switches: SharedFlow<Unit> = _switches.asSharedFlow()

    fun notifySwitched() {
        _switches.tryEmit(Unit)
    }
}
