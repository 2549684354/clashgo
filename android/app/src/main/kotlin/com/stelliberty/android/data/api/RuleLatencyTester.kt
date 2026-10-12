package com.stelliberty.android.data.api

import com.stelliberty.android.data.repository.SubscriptionProxyResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlin.time.TimeSource

// 一次真实访问的结果：延迟是发起请求到收到响应的耗时，body 供需要响应内容的探测
// （如 Cloudflare trace 解析出口 IP）使用。
class ProbeResult(val delay: Int, val body: String)

class RuleLatencyTester(
    private val proxyResolver: SubscriptionProxyResolver,
) {
    // 内核运行时对外提供的本地代理入口，延迟与出口探测都必须经它发出才是「实际访问」。
    suspend fun resolveEndpoint(): String? = proxyResolver.resolve()

    // 每次都新建客户端，复用连接池会让反复刷新的结果一次比一次偏低；同时禁止跟随跳转，
    // 否则会把跳转的往返也算进耗时。测不出来时返回 null，由调用方决定如何呈现，
    // 绝不降级到内核自报的缓存延迟，那不是真实访问的数字。
    //
    // 与内核 unified-delay 同口径：冷连接会把「拨号到节点 + 协议/TLS 握手 + 远程 DNS +
    // 连接站点」的建连开销全算进延迟，数值比内核面板系统性偏高一大截。因此先在同一连接上
    // 发一次暖场请求消化建连，再从第二个请求起计时，只衡量隧道内一次真实往返。
    suspend fun probe(url: String, endpoint: String?, timeoutMillis: Long): ProbeResult? {
        if (endpoint.isNullOrEmpty()) return null
        val client = buildClient(endpoint, timeoutMillis)
        return try {
            // 暖场失败即视为不可达，直接返回，不再叠加一次完整超时等待。
            try {
                client.get(url).bodyAsText()
            } catch (_: Throwable) {
                return null
            }
            val start = TimeSource.Monotonic.markNow()
            val response = client.get(url)
            ProbeResult(
                delay = start.elapsedNow().inWholeMilliseconds.toInt(),
                body = response.bodyAsText(),
            )
        } catch (_: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    private fun buildClient(endpoint: String, timeoutMillis: Long) = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = timeoutMillis
            requestTimeoutMillis = timeoutMillis
        }
        followRedirects = false
        engine { proxy = ProxyBuilder.http(Url(endpoint)) }
    }
}
