package com.clashgo.android.data.api

import com.clashgo.android.domain.model.LogEvent
import com.clashgo.android.domain.model.LogSource
import com.clashgo.android.domain.repository.MihomoRepository
import com.clashgo.android.util.AppLogger
import com.clashgo.android.util.DiagnosticLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class CoreLogCollector(
    private val scope: CoroutineScope,
    private val appVisible: StateFlow<Boolean>,
    private val logs: DiagnosticLogStore = AppLogger.logs,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var repository: MihomoRepository? = null
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun setRepository(repo: MihomoRepository?) {
        if (repository === repo) return
        job?.cancel()
        repository = repo
        logs.setCoreConnected(false)
        job = repo?.let {
            scope.launch(dispatcher) {
                // 内核向 /logs 推送时不看 log-level，只按订阅参数过滤；按实际生效的等级订阅，
                // 「核心日志」设置才能同时管住 mihomo.log 与这里。
                val level = repo.getConfig()
                    .onFailure { AppLogger.warn("CoreLogCollector", "Failed to read core log level, using info", it) }
                    .getOrNull()?.logLevel ?: "info"
                // 后台没有人在看诊断日志，内核 /logs 的每行推送照样是 WS 帧 + JSON 解析 + 环形缓冲
                // 写入；应用不可见即断开订阅，回前台重新接上，connected 状态随之如实翻转。
                appVisible.flatMapLatest { visible ->
                    if (!visible) {
                        logs.setCoreConnected(false)
                        emptyFlow()
                    } else {
                        repo.logsFlow(level)
                    }
                }.collect { event ->
                    synchronized(this@CoreLogCollector) {
                        if (repository !== repo) return@collect
                        when (event) {
                            LogEvent.Connected -> logs.setCoreConnected(true)
                            LogEvent.Disconnected -> logs.setCoreConnected(false)
                            is LogEvent.Message -> logs.append(LogSource.Core, event.message.type, "mihomo", event.message.payload)
                        }
                    }
                }
            }
        }
    }
}
