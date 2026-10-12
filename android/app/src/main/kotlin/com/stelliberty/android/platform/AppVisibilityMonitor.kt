package com.stelliberty.android.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 进程级「Activity 是否在前台」信号，MainActivity onStart/onStop 写入，appScope 里的
// 常驻消费者（自动测延迟、内核日志、连接页推送）以它为闸：后台时这些工作没有观看者，
// 挂起整条流让 WS 通道随之断开，回前台再重新订阅。
// 初值 false：进程被磁贴、开机自启等入口在后台拉起时保持不工作，直到 Activity 真正启动。
class AppVisibilityMonitor {

    private val _visible = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun setVisible(value: Boolean) {
        _visible.value = value
    }
}
