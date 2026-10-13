package com.clashgo.android.data.api

import com.clashgo.android.domain.model.ConnectionsResponse
import com.clashgo.android.domain.model.LogEvent
import com.clashgo.android.domain.model.LogMessage
import com.clashgo.android.domain.model.MemoryData
import com.clashgo.android.domain.model.TrafficData
import com.clashgo.android.util.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

class MihomoWebSocket(
    private val apiClient: MihomoApiClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val wsClient = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    private val _connectionState = MutableStateFlow(false)

    // 整体连接状态按存活通道计数；单条日志通道的状态随 LogEvent 发布。
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private var liveConnections = 0

    // close() 是终结语义：置位后所有数据流的循环退出，不再对已关闭的客户端重试。
    // 没有这个标志，close 后连接抛出的异常会被下面的通用 catch 吃掉并进入退避重试，
    // 只要有一条收集协程取消得稍晚，就留下一条每 1~30 秒空转的僵尸流。
    @Volatile
    private var closed = false

    @Synchronized
    private fun addLiveConnection(delta: Int) {
        liveConnections = (liveConnections + delta).coerceAtLeast(0)
        _connectionState.value = liveConnections > 0
    }

    fun trafficFlow(): Flow<TrafficData> = webSocketFlow(
        apiClient.getWebSocketUrl("/traffic"),
    ) { text -> json.decodeFromString<TrafficData>(text) }

    fun logsFlow(level: String): Flow<LogEvent> = webSocketFlow(
        apiClient.getWebSocketUrl("/logs?level=$level"),
        connectedEvent = LogEvent.Connected,
        disconnectedEvent = LogEvent.Disconnected,
    ) { text -> LogEvent.Message(json.decodeFromString<LogMessage>(text)) }

    fun memoryFlow(): Flow<MemoryData> = webSocketFlow(
        apiClient.getWebSocketUrl("/memory"),
    ) { text -> json.decodeFromString<MemoryData>(text) }

    fun connectionsFlow(): Flow<ConnectionsResponse> = webSocketFlow(
        apiClient.getWebSocketUrl("/connections"),
    ) { text -> json.decodeFromString<ConnectionsResponse>(text) }

    // 无限重连的数据流，除了被取消或 close() 不会自己结束。close() 后连接抛出的异常
    // 不再记为连接失败，也不进入重试等待，直接结束流。
    private fun <T> webSocketFlow(
        url: String,
        connectedEvent: T? = null,
        disconnectedEvent: T? = null,
        parser: (String) -> T,
    ): Flow<T> = flow {
        val channel = url.substringBefore('?').substringAfterLast('/')
        var backoffMs = INITIAL_BACKOFF_MS
        while (!closed && currentCoroutineContext().isActive) {
            var counted = false
            try {
                wsClient.webSocket(url) {
                    counted = true
                    addLiveConnection(1)
                    AppLogger.debug("MihomoWebSocket", "$channel channel connected")
                    backoffMs = INITIAL_BACKOFF_MS
                    connectedEvent?.let { emit(it) }
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val parsed = runCatching { parser(frame.readText()) }
                            .onFailure { AppLogger.warn("MihomoWebSocket", "Invalid $channel frame (${it.javaClass.simpleName})") }
                            .getOrNull() ?: continue
                        emit(parsed)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                if (closed) return@flow
                AppLogger.warn("MihomoWebSocket", "$channel channel failed (${error.javaClass.simpleName}); retry in ${backoffMs}ms")
            } finally {
                if (counted) addLiveConnection(-1)
            }
            if (closed) break
            disconnectedEvent?.let { emit(it) }
            AppLogger.debug("MihomoWebSocket", "$channel channel disconnected; retry in ${backoffMs}ms")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }.flowOn(Dispatchers.Default)

    fun close() {
        closed = true
        wsClient.close()
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
