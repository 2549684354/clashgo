package com.clashgo.android.domain.repository

import com.clashgo.android.domain.model.ProxyPreview

interface ProxyPreviewRepository {
    // 订阅不存在、配置缺失或解析失败时返回 null，调用方按空列表渲染。
    suspend fun load(subscriptionId: String): ProxyPreview?

    // 内核未运行时对预览节点做与运行时同口径的测速，返回毫秒；订阅缺失、节点不存在或测速失败返回 null。
    suspend fun testNodeDelay(subscriptionId: String, nodeName: String, timeoutMs: Int): Int?
}
