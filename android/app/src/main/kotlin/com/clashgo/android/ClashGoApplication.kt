package com.clashgo.android

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import com.clashgo.android.data.api.AutoDelayTester
import com.clashgo.android.data.api.MihomoConnectionManager
import com.clashgo.android.data.bridge.ClashGoCoreBridge
import com.clashgo.android.di.androidAppModule
import com.clashgo.android.di.androidPlatformModule
import com.clashgo.android.di.dataModule
import com.clashgo.android.di.viewModelModule
import com.clashgo.android.platform.PlatformStorage
import com.clashgo.android.platform.ProxyServiceBridge
import com.clashgo.android.platform.StorageKeys
import com.clashgo.android.platform.TunMode
import com.clashgo.android.platform.initToastPlatform
import com.clashgo.android.service.NotificationHelper
import com.clashgo.android.service.ProfileFileOps
import com.clashgo.android.service.ProfileUpdateScheduler
import com.clashgo.android.util.AppLogger
import kotlin.concurrent.thread
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ClashGoApplication : Application() {

    val connectionManager: MihomoConnectionManager by inject()

    private val updateScheduler: ProfileUpdateScheduler by inject()

    private val autoDelayTester: AutoDelayTester by inject()

    private val storage: PlatformStorage by inject()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 排在最前：后面每一步都要打日志，未初始化时文件写入会静默降级、只剩 logcat。
        AppLogger.initialize(this, PlatformStorage(this).getString(StorageKeys.APP_LOG_LEVEL, AppLogger.DEFAULT_LEVEL))
        startKoin {
            androidContext(this@ClashGoApplication)
            modules(dataModule, androidPlatformModule, androidAppModule, viewModelModule)
        }
        // 必须在任何人读桥之前填：MainActivity 与通知都在 onCreate 之后才起来，这里是唯一
        // 既拿得到设置、又早于全部读取方的位置。
        ProxyServiceBridge.setSelectedTunMode(
            TunMode.fromStorage(storage.getString(StorageKeys.TUN_MODE, TunMode.Vpn.storageValue))
        )
        initToastPlatform(this)
        // 渠道创建是纯 binder 调用，与启动渲染无关，丢后台避免占主线程。
        thread(name = "notification-channels") { NotificationHelper.createChannels(this) }
        ClashGoCoreBridge.init(homeDir = ProfileFileOps.getGeodataDir(this).absolutePath)
        // 解压不能堵住启动；读地理数据的地方各自等 awaitGeodata()。
        thread(name = "geodata-extract") { ProfileFileOps.extractGeodata(this) }
        updateScheduler.start()
        autoDelayTester.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = storage.getString(StorageKeys.PREDICTIVE_BACK, "false") == "true"
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }
    }

    companion object {
        lateinit var instance: ClashGoApplication
            private set

        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
