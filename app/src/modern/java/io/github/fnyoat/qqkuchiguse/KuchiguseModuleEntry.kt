/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge
import io.github.fnyoat.qqkuchiguse.hook.KuchiguseCore
import io.github.fnyoat.qqkuchiguse.hook.KuchiguseCore.TAG
import io.github.fnyoat.qqkuchiguse.hook.Reflect
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * modern 风味入口：官方 libxposed API（`XposedModule`）。与 legacy 行为一致，业务逻辑同样在
 * [KuchiguseCore]，差别只在装 hook 的方式：这里是 `XposedInterface#hook` + `Chain`，不是
 * `XposedBridge.hookMethod`。
 *
 * 冷启动：`onPackageLoaded` 跑在 `handleBindApplication` 里，此时不能立刻 findClass/hook QQ 的类
 * （会在主线程强制加载+校验，抢在 QQ 自己的 dex 准备之前，拖慢冷启动几秒），所以只挂一个
 * `Application.onCreate`，真正安装挪到之后的后台线程。
 */
class KuchiguseModuleEntry : XposedModule() {

    private var hostLoader: ClassLoader? = null

    /** 启动初始化（读配置 + 生命周期回调 + 起安装线程）每进程只跑一次。 */
    @Volatile
    private var startupDone = false

    /** hook 安装每进程只跑一次。 */
    @Volatile
    private var hooksInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.i(TAG, "modern module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != ConfigBridge.HOST_PACKAGE) {
            return
        }
        hostLoader = param.defaultClassLoader
        KuchiguseCore.setProcessName(currentProcessName())
        Log.i(TAG, "loaded in ${KuchiguseCore.processName}")
        hookApplicationStartup()
    }

    private fun currentProcessName(): String {
        return try {
            val pid = android.os.Process.myPid()
            val stream = java.io.FileInputStream("/proc/$pid/cmdline")
            val name = stream.readBytes().toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            stream.close()
            name.ifEmpty { "unknown" }
        } catch (t: Throwable) {
            "unknown"
        }
    }

    private fun hookApplicationStartup() {
        try {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(onCreate).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        onHostApplicationCreated(chain.thisObject as? Application)
                        return result
                    }
                })
        } catch (t: Throwable) {
            Log.e(TAG, "hook Application.onCreate failed", t)
        }
    }

    private fun onHostApplicationCreated(app: Application?) {
        if (app == null) return
        synchronized(this) {
            if (startupDone) return
            startupDone = true
        }
        KuchiguseCore.bind(app, KuchiguseCore.processName)
        // 只有主进程才需要这套代理：齿轮在聊天界面里被点到，而 PackageManager /
        // ActivityManager / classloader 桥都会改动宿主进程的结构。给 MSF、tool 这类
        // 辅助进程也装一遍只会拖垮它们，之前 QQ 就是因此反复重启、点齿轮毫无反应。
        if (KuchiguseCore.processName == ConfigBridge.HOST_PACKAGE) {
            hostLoader?.let { ActProxy.install(app, it) }
            hookActivityTakeover()
        } else {
            Log.i(TAG, "skip ActProxy in ${KuchiguseCore.processName}")
        }
        try {
            val cfg = ConfigBridge.loadIntoCache(app)
            Log.i(
                TAG,
                "config loaded at process start: enabled=${cfg.enabled} " +
                    "affixes=${cfg.affixes.size} scope=${cfg.scope}",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "config preload failed", t)
        }
        try {
            app.registerActivityLifecycleCallbacks(HostLifecycleCallbacks())
        } catch (t: Throwable) {
            Log.w(TAG, "registerActivityLifecycleCallbacks failed", t)
        }
        val t = Thread({
            try {
                installHooks()
            } catch (t2: Throwable) {
                Log.e(TAG, "installHooks failed", t2)
            }
        }, "kuchiguse-install")
        t.isDaemon = true
        t.start()
    }

    /** 重活：找类 + 装 hook。在后台线程执行，不占 QQ 启动主线程。 */
    private fun installHooks() {
        synchronized(this) {
            if (hooksInstalled) return
            hooksInstalled = true
        }
        val loader = hostLoader ?: return
        val isMain = KuchiguseCore.isMainProcess()
        try {
            val msgService = Class.forName("com.tencent.qqnt.kernel.api.impl.MsgService", false, loader)
            val sendMsg = msgService.getDeclaredMethod(
                "sendMsg",
                java.lang.Long.TYPE,
                contactClass(loader),
                java.util.ArrayList::class.java,
                java.util.HashMap::class.java,
                Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback",
                    false,
                    loader,
                ),
            )
            hook(sendMsg).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val args = chain.args
                        val contact = if (args.size >= 2) args[1] else null
                        // Do NOT update session key from sendMsg; only VM/ChatPie paths update it
                        KuchiguseCore.applyToSendMsgArgs(args, contact)
                        return chain.proceed()
                    }
                })
            Log.i(TAG, "sendMsg hooked in ${KuchiguseCore.processName}")

            if (isMain) {
                hookChatLifecycle(loader)
            }
        } catch (t: Throwable) {
            // Do not crash the host QQ process when the hook fails.
            Log.e(TAG, "hook install failed", t)
        }
    }

    /**
     * 在 `Instrumentation.newActivity` 这一环接管 QQ 进程里拉起的设置页/名单页。
     *
     * 只 hook 这一个方法，不替换 `ActivityThread.mInstrumentation`：那个对象被 QQ 和框架到处
     * 使用，换成未 init 的裸实例会在 QQ 启动阶段就 NPE（logcat: failed to complete startup）。
     * 基类会因为 SettingsActivity 不在 QQ 的 DexPathList 里而抛 ClassNotFoundException，接住后
     * 用模块 classloader 造出真正的 Activity。
     *
     * 另三组聊天信号分工明确：[hookDraftVmSignals] 给「聊天页开/切走/关掉」驱动气泡显隐；
     * [hookSessionTracking] 给「当前会话是谁」（压栈/出栈）；[hookAppResumeSignal] 在 QQ 回
     * 前台时把气泡挂回去。
     */
    private fun hookActivityTakeover() {
        try {
            val m = Class.forName("android.app.Instrumentation").getDeclaredMethod(
                "newActivity", ClassLoader::class.java, String::class.java, Intent::class.java,
            )
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val className = chain.args.getOrNull(1) as? String
                        Log.i(TAG, "newActivity: $className")
                        if (ActProxy.isProxyActivity(className)) {
                            val a = ActProxy.takeOverActivity(className, RuntimeException("stub"))
                            if (a != null) return a
                        }
                        return chain.proceed()
                    }
                })
            Log.i(TAG, "activity takeover hook installed")
        } catch (t: Throwable) {
            Log.w(TAG, "activity takeover hook failed: ${t.message}", t)
        }
        hookActivityResourceInject()
    }

    /**
     * 在 [android.app.Instrumentation.callActivityOnCreate] 上注入模块资源。
     *
     * ActProxy 自己 new 出来的 SettingsActivity 在 [ActProxy.takeOverActivity]
     * 那一刻还没有 attach()，取 resources 会 NPE；而这里既已 attach 完成，又早于
     * onCreate 的第一行，正好是唯一安全且不晚的时机。
     */
    private fun hookActivityResourceInject() {
        try {
            val m = Class.forName("android.app.Instrumentation").getDeclaredMethod(
                "callActivityOnCreate", android.app.Activity::class.java, Bundle::class.java,
            )
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val activity = chain.args.getOrNull(0) as? android.app.Activity
                        if (activity != null) ActProxy.onProxyActivityCreating(activity)
                        return chain.proceed()
                    }
                })
            Log.i(TAG, "activity resource inject hook installed")
        } catch (t: Throwable) {
            Log.w(TAG, "activity resource inject hook failed: ${t.message}", t)
        }
    }

    private fun hookChatLifecycle(loader: ClassLoader) {
        hookDraftVmSignals(loader)
        hookSessionTracking(loader)
        hookAppResumeSignal(loader)
    }

    /**
     * 草稿 VM 的生命周期。`InputDraftVMDelegate` 每次进入聊天页都会新建一个，
     * 所以它的构造 / onStop / onDestroy 是稳定的显隐信号。
     */
    private fun hookDraftVmSignals(loader: ClassLoader) {
        val clsName = DRAFT_VM
        val clazz = try {
            Class.forName(clsName, false, loader)
        } catch (t: Throwable) {
            Log.w(TAG, "class $clsName not found", t)
            return
        }
        Log.i(TAG, "found chat class $clsName")
        for (ctor in clazz.declaredConstructors) {
            installHook(ctor, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    Log.i(TAG, "chat page opened: draft vm created")
                    KuchiguseCore.onChatShown()
                    val result = chain.proceed()
                    // 构造返回时 VM 里通常已经注入好会话对象，顺手捕获一次
                    KuchiguseCore.onChatVmBuilt(chain.thisObject)
                    return result
                }
            }, "$clsName.<init> -> chat shown")
        }
        // 切走（进后台、跳别的 Activity）：只收气泡，会话还在，栈不动
        for (m in clazz.declaredMethods.filter { it.name in HIDE_METHODS }) {
            installHook(m, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    KuchiguseCore.onChatHidden()
                    return chain.proceed()
                }
            }, "$clsName.${m.name} -> chat stopped")
        }
        // 真退出会话：清栈。
        // 这里**不能**用 ChatPie.onDestroy —— 切换聊天时旧页的 onDestroy 也会触发，照它
        // 弹栈会把紧接着要用的会话提前弹掉（实测第二个聊天就没会话了）。草稿 VM 是每页
        // 一个，它销毁即这一页结束；KuchiguseCore 里做了防抖，跨过「切聊天」。
        for (m in clazz.declaredMethods.filter { it.name == "onDestroy" }) {
            installHook(m, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    Log.i(TAG, "chat page closing: draft vm destroyed")
                    KuchiguseCore.onChatPageClosed()
                    return result
                }
            }, "$clsName.onDestroy -> chat closed")
        }
    }

    /**
     * 当前会话是谁 —— 维护一条「AIO 创建时压栈、聊天页结束时弹栈」的栈。
     *
     * 这里额外加了两个兜底捕获点（AIOParam / AIOSession 的构造与 setter），因为 NT 侧的
     * 类名和字段名逐版本变动，只押一个点很容易整个取不到。
     */
    private fun hookSessionTracking(loader: ClassLoader) {
        val pieClass = try {
            Class.forName(CHAT_PIE, false, loader)
        } catch (t: Throwable) {
            Log.w(TAG, "class $CHAT_PIE not found; session tracking limited", t)
            null
        }
        if (pieClass != null) {
            val pieMethods = Reflect.instanceMethods(pieClass)
            // AIO 创建入口。不写死方法名：名字里同时含
            // aio 和 创建/初始化 语义的全部挂上，混淆换了字母也照样命中。
            for (m in pieMethods.filter { isAioCreate(it.name) }) {
                installHook(m, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        KuchiguseCore.onChatPieTouched(chain.thisObject)
                        return result
                    }
                }, "${m.declaringClass.name}.${m.name} -> aio create")
            }
            // 聊天页 View 生命周期：进聊天页时就把「主人」认下来。子类构造器没法提前
            // 枚举，光靠构造 hook 的话 owner 会一直是 null（会话就归不到任何聊天页上）。
            for (m in pieMethods.filter { isPieLifecycle(it) }) {
                installHook(m, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        KuchiguseCore.onChatPieTouched(chain.thisObject)
                        return result
                    }
                }, "${m.declaringClass.name}.${m.name} -> pie owner")
            }
            // 这里**不**挂 ChatPie.onDestroy 做弹栈：切换聊天时旧页的 onDestroy 也会
            // 触发，弹栈会把紧接着要用的会话提前弹掉。出栈交给草稿 VM 的 onDestroy。
            // 构造：AIO 是在构造之后才挂上去的，所以记下最新 pie 并补一次延迟捕获
            for (ctor in Reflect.hierarchyConstructors(pieClass)) {
                installHook(ctor, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        // 就这一次捕获。曾经还补一个 500ms 后的延迟捕获，等 AIO 挂上来，
                        // 但它会在「开聊天后很快退出」时把已经关掉的页面重新压回栈里 ——
                        // 草稿 VM 的清栈 debounce 只有 250ms，早就跑完了。
                        KuchiguseCore.onChatPieCreated(chain.thisObject)
                        return result
                    }
                }, "$CHAT_PIE.<init> -> session capture")
            }
        }

        // 兜底捕获点：AIOParam / AIOSession 的构造与 setter 就是「会话被绑定」的瞬间。
        for (name in arrayOf(AIO_PARAM, AIO_SESSION)) {
            val cls = try {
                Class.forName(name, false, loader)
            } catch (t: Throwable) {
                Log.w(TAG, "class $name not found", t)
                continue
            }
            for (ctor in cls.declaredConstructors) {
                installHook(ctor, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        KuchiguseCore.onChatVmBuilt(chain.thisObject)
                        return result
                    }
                }, "${cls.simpleName}.<init> -> session capture")
            }
            for (m in Reflect.instanceMethods(cls).filter { it.name.startsWith("set") && it.parameterTypes.size == 1 }) {
                installHook(m, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        KuchiguseCore.onChatVmBuilt(chain.thisObject)
                        return result
                    }
                }, "${cls.simpleName}.${m.name} -> session capture")
            }
        }
    }

    /** 名字里同时有 aio 和 创建/初始化 语义 —— 就是 AIO 创建入口。 */
    private fun isAioCreate(name: String): Boolean {
        if (!name.contains("aio", ignoreCase = true)) return false
        return AIO_LIFECYCLE_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * 聊天页 View 生命周期里一定会走、而且是 **QQ 自己声明** 的方法。
     *
     * 必须排除 `android.view.View` 上声明的那些：挂上去的话整个 QQ 里每个 View 都会
     * 走我们的回调。所以只认 `com.tencent` 声明处。
     */
    private fun isPieLifecycle(m: Method): Boolean {
        if (!m.declaringClass.name.startsWith("com.tencent")) return false
        if (m.parameterTypes.isNotEmpty()) return false
        return m.name in PIE_LIFECYCLE
    }

    /**
     * QQ 回到前台时把气泡挂回去（聊天 VM 还活着的场景）。
     * QQ 的 NT 类混淆，onResume 可能在父类上，所以沿继承链往上找。
     */
    private fun hookAppResumeSignal(loader: ClassLoader) {
        try {
            var activityClass: Class<*>? = Class.forName(
                "com.tencent.mobileqq.activity.SplashActivity",
                false,
                loader,
            )
            var resumeMethod: Method? = null
            while (activityClass != null && resumeMethod == null) {
                resumeMethod = try {
                    activityClass.getDeclaredMethod("onResume")
                } catch (t: Throwable) {
                    null
                }
                if (resumeMethod == null) {
                    activityClass = activityClass.superclass
                }
            }
            if (resumeMethod != null) {
                installHook(resumeMethod, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        KuchiguseCore.onAppResumed()
                        return chain.proceed()
                    }
                }, "SplashActivity.onResume -> app resumed")
            } else {
                Log.w(TAG, "SplashActivity.onResume not found in class hierarchy")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SplashActivity.onResume hook failed", t)
        }
    }

    private fun installHook(target: java.lang.reflect.Executable, hooker: XposedInterface.Hooker, label: String) {
        hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
        Log.i(TAG, "hooked $label")
    }

    private fun contactClass(loader: ClassLoader): Class<*> {
        return try {
            Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.Contact", false, loader)
        } catch (t: Throwable) {
            Class.forName("com.tencent.qqnt.kernel.nativeinterface.Contact", false, loader)
        }
    }

    /**
     * 宿主 Activity 生命周期：每次 resume 让悬浮球刷新一次外观/重新附着。
     * 设置页在另一个进程，改完配置回来时靠这个把新配置显示到气泡上。
     */
    private inner class HostLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            KuchiguseCore.onHostActivityResumed(activity)
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
        }

        override fun onActivityStarted(activity: android.app.Activity) {
        }

        override fun onActivityPaused(activity: android.app.Activity) {
        }

        override fun onActivityStopped(activity: android.app.Activity) {
        }

        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {
        }

        override fun onActivityDestroyed(activity: android.app.Activity) {
        }
    }

    companion object {
        private const val DRAFT_VM = "com.tencent.mobileqq.aio.input.draft.InputDraftVMDelegate"
        private const val CHAT_PIE = "com.tencent.aio.base.chat.ChatPie"
        private const val AIO_PARAM = "com.tencent.aio.data.AIOParam"
        private const val AIO_SESSION = "com.tencent.aio.data.AIOSession"

        /** 名字里同时含 aio + 这些语义的就是 AIO 创建入口。 */
        private val AIO_LIFECYCLE_HINTS = arrayOf("create", "init", "attach", "bind")

        /** 聊天页 View 生命周期（只认 QQ 自己声明的那些）。 */
        private val PIE_LIFECYCLE = setOf(
            "onAttachedToWindow",
            "onDetachedFromWindow",
            "onFinishInflate",
            "onCreate",
            "onStart",
            "onResume",
            "onInit",
            "init",
            "onViewCreated",
            "onSetup",
            "onFirstShow",
        )

        private val HIDE_METHODS = setOf("onStop", "onPause", "onDetach")

        /** 手动同步入口（悬浮面板「立刻重载配置」）。 */
        @JvmStatic
        fun syncConfigNow(context: android.content.Context) = KuchiguseCore.syncConfigNow(context)
    }
}
