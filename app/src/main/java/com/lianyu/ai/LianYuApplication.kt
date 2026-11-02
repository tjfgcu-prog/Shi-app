package com.lianyu.ai

import android.app.Application
import android.content.res.Configuration

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.DefaultCompanionSeeder

import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository

import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.YandereModeManager
import com.lianyu.ai.domain.CompanionProvider
import com.lianyu.ai.domain.AiServiceProvider

import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.UserProfileProvider

import com.lianyu.ai.feature.notification.NotificationHelper


import com.lianyu.ai.network.AiService
import com.lianyu.ai.network.NtpTimeProvider

import com.lianyu.ai.uicommon.component.ChatBackgroundCache
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class LianYuApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {

    

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizeBytes(128 * 1024 * 1024).build()
        }
        .build()

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        // 关闭 JVM 层 DNS 缓存，网络切换后立刻重新解析
        java.security.Security.setProperty("networkaddress.cache.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        super.onCreate()
        installCrashRecorder()
        instance = this
        // 新进程启动 = 本地模型一定不在内存里：把开关复位为关，保证开关等于"模型是否真的在运行"
        getSharedPreferences("gguf_model_prefs", MODE_PRIVATE).edit().putBoolean("gguf_enabled", false).apply()
        // 诊断日志写文件，放到后台线程，不拖慢冷启动
        bgScope.launch { logPreviousExitReasons() }
        initBusiness(this)
        // 冷启动清空媒体解密临时缓存，避免明文副本无限堆积（源文件一直是加密的，清空不丢数据）
        try {
            java.io.File(cacheDir, "media_plain").deleteRecursively()
        } catch (_: Exception) { }
    }

    /**
     * [诊断] 记录未捕获的 Java/Kotlin 异常：写进 files/chatvm_debug.log，以 [CRASH] 开头。
     * 只记异常类型 + 栈帧（类名/方法/行号），不记异常文字描述（可能带聊天内容）。
     * 记完后原样交还给系统默认处理，App 的闪退行为不变。
     * release 包的类名是压缩过的，需配合 CI 上传的 mapping.txt 还原。
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sb = StringBuilder()
                val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                sb.append(fmt.format(java.util.Date())).append(" [CRASH] thread=").append(thread.name)
                    .append(" ").append(error.javaClass.name).append('\n')
                var cur: Throwable? = error
                var depth = 0
                while (cur != null && depth < 3) {
                    if (depth > 0) sb.append("  Caused by ").append(cur.javaClass.name).append('\n')
                    for (el in cur.stackTrace.take(if (depth == 0) 14 else 10)) {
                        sb.append("    at ").append(el.className).append('.').append(el.methodName)
                            .append('(').append(el.fileName ?: "?").append(':').append(el.lineNumber).append(")\n")
                    }
                    cur = cur.cause
                    depth++
                }
                val logFile = java.io.File(filesDir, "chatvm_debug.log")
                if (logFile.exists() && logFile.length() > 2L * 1024 * 1024) logFile.delete()
                logFile.appendText(sb.toString())
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * [诊断] 把"上一个进程为什么没了"写进 files/chatvm_debug.log（仅 Android 11+）。
     * REASON_LOW_MEMORY / REASON_SIGNALED(9) = 被系统因内存杀掉；
     * REASON_CRASH_NATIVE = so 库（llama/sherpa）崩溃；REASON_CRASH = Java 异常。
     */
    private fun logPreviousExitReasons() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            val lines = am.getHistoricalProcessExitReasons(packageName, 0, 5).joinToString("\n") { r ->
                "${fmt.format(java.util.Date(r.timestamp))} [ExitInfo] reason=${r.reason} " +
                    "status=${r.status} importance=${r.importance} pss=${r.pss / 1024}MB rss=${r.rss / 1024}MB desc=${r.description}"
            }
            val logFile = java.io.File(filesDir, "chatvm_debug.log")
            // 调试日志只追加不清理会越来越大：超过 2MB 就从头记
            if (logFile.exists() && logFile.length() > 2L * 1024 * 1024) logFile.delete()
            logFile.appendText("$lines\n")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFixedLocale()
    }

    // App 切到后台（UI 不可见）时同步落盘一次待写入的记忆，防止系统随后杀掉进程
    // 导致 schedulePersist() 里排队的异步写入还没执行就丢失（"核心记忆"消失的根因）。
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching {
                com.lianyu.ai.feature.memory.engine.MemoryManager.getInstance(this).flushAllPending()
            }
        }
    }

    override fun onTerminate() {
        bgScope.launch {
            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
        }
        ServiceRegistry.clear()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: LianYuApplication
            private set

        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun initBusiness(app: Application) {
            SecureLog.init(com.lianyu.ai.BuildConfig.DEBUG)
            applyFixedLocale()
            AiService.initialize(app)
            NtpTimeProvider.initialize(app)
            registerServiceProviders(app)
            

            // 注入应用级后台作用域，供跨越 ViewModel 生命周期的任务使用
            com.lianyu.ai.common.ApplicationScopeProvider.init(bgScope)

            // Seed default companion asynchronously — must exist before any chat opens
            bgScope.launch { seedDefaultCompanion(app) }

            bgScope.launch { preloadBackground(app) }
            bgScope.launch { initPush(app) }
            
            bgScope.launch { autoBackupDatabase(app) }

            bgScope.launch { initYandereMode(app) }
        }

        private suspend fun initYandereMode(app: Application) {
            try {
                com.lianyu.ai.feature.notification.YandereMessageWorker.schedule(app)
                if (AppSettingsStore(app).getYandereModeEnabled()) {
                    ServiceRegistry.getOrThrow(YandereModeManager::class.java).start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("LianYuApplication", "initYandereMode failed", e)
            }
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun seedDefaultCompanion(app: Application) {
            DefaultCompanionSeeder.seedIfNeeded(app)
        }

        private fun initPush(app: Application) {
    NotificationHelper.createNotificationChannel(app)
        }

        

        private fun autoBackupDatabase(app: Application) {
            // [M6 FIX] 备份涉及文件 IO，已从 AppDatabase.buildDatabase 主路径移除，
            // 在此异步执行，不阻塞首屏渲染。同时顺带清理过期备份。
            runCatching { AppDatabase.autoBackupIfNeeded(app.applicationContext) }
            runCatching { AppDatabase.clearOldBackups(app.applicationContext) }
        }

        private fun registerServiceProviders(app: Application) {
            // ── Repository 单例注册 ──
            // 统一 Repository 获取方式，供 QQ Bot 等跨模块消费者通过 ServiceRegistry 获取。
            ServiceRegistry.registerSingleton(CompanionRepository::class.java) {
                CompanionRepository(AppDatabase.getDatabase(app).companionDao())
            }
            ServiceRegistry.registerSingleton(ChatRepository::class.java) {
                ChatRepository(AppDatabase.getDatabase(app).chatMessageDao())
            }
            
            ServiceRegistry.registerSingleton(UserRepository::class.java) {
                UserRepository(app)
            }

            // ── 跨 feature 服务接口注册 ──
            ServiceRegistry.registerSingleton(LocalModelProvider::class.java) {
                com.lianyu.ai.feature.localmodel.LocalModelProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {
                com.lianyu.ai.feature.profile.UserProfileProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.lianyu.ai.feature.companion.CompanionProviderImpl(app)
            }
            // MemoryProvider：跨会话记忆上下文与提取（feature:memory 实现，core:network/feature:groupchat 消费）
            // 必须在 AiService 之前注册，因为 AiService.init 会通过 ServiceRegistry 获取 MemoryProvider
            ServiceRegistry.registerSingleton(MemoryProvider::class.java) {
                com.lianyu.ai.feature.memory.engine.MemoryManager.getInstance(app)
            }
            ServiceRegistry.registerSingleton(AiServiceProvider::class.java) {
                AiService(app)
            }
            ServiceRegistry.registerSingleton(YandereModeManager::class.java) {
                YandereModeManager(app)
            }

            

            ServiceRegistry.markInitialized()
        }

        

        private fun applyFixedLocale() {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
                }
    }
}
