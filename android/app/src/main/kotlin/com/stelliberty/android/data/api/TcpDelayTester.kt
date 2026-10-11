package com.stelliberty.android.data.api

import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 内核未运行时没有 REST API 可用，节点延迟退化为对 server:port 的 TCP 握手耗时：
// 数值口径与内核的 HTTP 204 测速不同（不含 TLS 与 HTTP 往返），只用于离线时横向比较节点可达性。
object TcpDelayTester {

    suspend fun test(host: String, port: Int, timeoutMs: Int = 5000): Int =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    // 小于 1ms 的本机/内网节点归一到 1，避免和「未测」的 0 混淆。
                    ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                -1
            }
        }
}
