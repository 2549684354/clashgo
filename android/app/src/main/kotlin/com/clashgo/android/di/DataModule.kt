package com.clashgo.android.di

import com.clashgo.android.R
import com.clashgo.android.data.api.AutoDelayTester
import com.clashgo.android.data.api.MihomoConnectionManager
import com.clashgo.android.data.api.RuleLatencyTester
import com.clashgo.android.data.repository.AppUpdateRepositoryImpl
import com.clashgo.android.data.repository.ChainProxyRepositoryImpl
import com.clashgo.android.data.repository.OverrideJsonStore
import com.clashgo.android.data.repository.OverrideProfileRepositoryImpl
import com.clashgo.android.data.repository.ProfileProcessor
import com.clashgo.android.data.repository.ProxyPreviewRepositoryImpl
import com.clashgo.android.data.repository.RuleOverrideRepositoryImpl
import com.clashgo.android.data.repository.SubscriptionProxyResolver
import com.clashgo.android.data.repository.SubscriptionRepositoryImpl
import com.clashgo.android.data.store.OverrideProfileStore
import com.clashgo.android.data.store.ProfileTransformWriter
import com.clashgo.android.data.store.ProxyDelayStore
import com.clashgo.android.data.store.ProxySelectionStore
import com.clashgo.android.data.store.RuleOverrideStore
import com.clashgo.android.data.store.SubscriptionStore
import com.clashgo.android.domain.repository.AppUpdateRepository
import com.clashgo.android.domain.repository.ChainProxyRepository
import com.clashgo.android.domain.repository.OverrideProfileRepository
import com.clashgo.android.domain.repository.ProxyPreviewRepository
import com.clashgo.android.domain.repository.RuleOverrideRepository
import com.clashgo.android.domain.repository.SubscriptionRepository
import com.clashgo.android.platform.AppVisibilityMonitor
import com.clashgo.android.util.AppLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single<CoroutineScope> {
        val handler = CoroutineExceptionHandler { _, e ->
            AppLogger.error("AppScope", "uncaught in shared scope", e)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    single { SubscriptionStore(get(), get()) }
    single { ProxySelectionStore(get(), get()) }
    single { ProxyDelayStore(get(), get()) }
    single { OverrideProfileStore(get(), get()) }
    single { RuleOverrideStore(get(), get()) }
    single { ProfileTransformWriter(get(), get(), get()) }

    single { OverrideJsonStore(get(), get()) }
    single { SubscriptionProxyResolver(get()) }
    single<AppUpdateRepository> { AppUpdateRepositoryImpl(get()) }
    single { RuleLatencyTester(get()) }
    // 两个可见性参数是 StateFlow<Boolean>：不能走裸 get()（会按 StateFlow 类型找定义，
    // v1.1.0 正式版因此启动即崩），必须显式取自 AppVisibilityMonitor。
    single {
        MihomoConnectionManager(
            appVisible = get<AppVisibilityMonitor>().visible,
            scope = get(),
            overrideStore = get(),
        )
    }
    single {
        AutoDelayTester(
            scope = get(),
            connectionManager = get(),
            subscriptions = get(),
            appVisible = get<AppVisibilityMonitor>().visible,
        )
    }

    single {
        SubscriptionRepositoryImpl(
            store = get(),
            proxySelections = get(),
            proxyDelays = get(),
            ruleOverrides = get(),
            storage = get(),
            scope = get(),
        )
    }
    single<SubscriptionRepository> { get<SubscriptionRepositoryImpl>() }

    single<OverrideProfileRepository> {
        OverrideProfileRepositoryImpl(
            store = get(),
            transformWriter = get(),
            subscriptionStore = get(),
            subscriptions = get(),
            fileManager = get(),
            proxyResolver = get(),
            scope = get(),
        )
    }

    single<ChainProxyRepository> {
        ChainProxyRepositoryImpl(
            subscriptionStore = get(),
            subscriptions = get(),
            transformWriter = get(),
            fileManager = get(),
        )
    }

    single<RuleOverrideRepository> {
        RuleOverrideRepositoryImpl(
            store = get(),
            subscriptionStore = get(),
            transformWriter = get(),
            fileManager = get(),
        )
    }

    single<ProxyPreviewRepository> {
        ProxyPreviewRepositoryImpl(
            subscriptionStore = get(),
            transformWriter = get(),
            fileManager = get(),
            overrideStore = get(),
        )
    }

    factory {
        ProfileProcessor(
            repo = get(),
            fileManager = get(),
            defaultProfileName = androidContext().getString(R.string.subscription_default_name),
            proxyResolver = get(),
        )
    }
}
