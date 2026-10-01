/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse

import android.app.Application
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge
import io.github.fnyoat.qqkuchiguse.hook.KuchiguseCore
import io.github.fnyoat.qqkuchiguse.hook.KuchiguseCore.TAG
import io.github.fnyoat.qqkuchiguse.hook.Reflect
import java.lang.reflect.Method

/**
 * legacy 风味入口：经典 Xposed API（`IXposedHookLoadPackage`）。
 *
 * 只做 hook 装配，业务逻辑全在 [KuchiguseCore]，与 modern 风味的
 * `XposedModule` 入口共用同一份实现。
 *
 * 冷启动注意：本回调跑在 `ActivityThread.handleBindApplication` 里
 * （`LoadedApk.getClassLoader` 内部），此时**不能** findClass/hook QQ 的类——
 * 在主线程上强制加载+校验 MsgService / InputDraftVMDelegate / ChatPie 会抢在
 * QQ 自己的 dex 后台准备之前，把冷启动拖慢好几秒。所以这里只挂一个
 * `Application.onCreate`，真正的安装挪到 onCreate 之后的后台线程。
 */
class KuchiguseModuleEntry : de.robv.android.xposed.IXposedHookLoadPackage {

    private var hostLoader: ClassLoader? = null

    /** `Application.onCreate` 挂一次就够：同机上别的模块可能重复触发它。 */
    @Volatile
    private var startupHooked = false

    /** 启动初始化（读配置 + 生命周期回调 + 起安装线程）每进程只跑一次。 */
    @Volatile
    private var startupDone = false

    /** hook 安装每进程只跑一次。 */
    @Volatile
    private var hooksInstalled = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ConfigBridge.HOST_PACKAGE) {
            return
        }
        hostLoader = lpparam.classLoader
        KuchiguseCore.setProcessName(currentProcessName())
        Log.i(TAG, "loaded in ${KuchiguseCore.processName}")
        synchronized(this) {
            if (startupHooked) return
            startupHooked = true
        }
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

    /**
     * 挂在宿主 `Application.onCreate` 上（handleLoadPackage 阶段
     * currentApplication() 还是 null，会抛 "no current application"）。
     *
     * onCreate 之后：
     *  - 读一次配置写进内存缓存（之后每条消息只读缓存，手动同步才回磁盘）；
     *  - 注册 ActivityLifecycleCallbacks（驱动悬浮球跟随聊天界面）；
     *  - 把找类/装 hook 的重活丢到**后台线程**，不与 QQ 冷启动抢主线程。
     */
    private fun hookApplicationStartup() {
        try {
            XposedBridge.hookAllMethods(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        synchronized(this@KuchiguseModuleEntry) {
                            if (startupDone) return
                            startupDone = true
                        }
                        KuchiguseCore.bind(app, KuchiguseCore.processName)
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
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "hook Application.onCreate failed", t)
        }
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
            val msgService = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.api.impl.MsgService",
                loader,
            )
            val sendMsg = XposedHelpers.findMethodExact(
                msgService,
                "sendMsg",
                Long::class.java,
                contactClass(loader),
                java.util.ArrayList::class.java,
                java.util.HashMap::class.java,
                XposedHelpers.findClass(
                    "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback",
                    loader,
                ),
            )
            XposedBridge.hookMethod(sendMsg, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    val contact = if (args.size >= 2) args[1] else null
                    // Do NOT update session key from sendMsg; only VM/ChatPie paths update it
                    KuchiguseCore.applyToSendMsgArgs(args.toList(), contact)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
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
     * 聊天相关的三组信号，分工明确：
     *  - [hookDraftVmSignals]：草稿 VM 给出「聊天页开 / 切走 / 关掉」，驱动气泡显隐；
     *  - [hookSessionTracking]：ChatPie + AIO 数据类给出「当前会话是谁」（压栈 / 出栈）；
     *  - [hookAppResumeSignal]：QQ 回前台时把气泡挂回去。
     */
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
            XposedHelpers.findClass(clsName, loader)
        } catch (t: Throwable) {
            Log.w(TAG, "class $clsName not found", t)
            return
        }
        Log.i(TAG, "found chat class $clsName")
        try {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Log.i(TAG, "chat page opened: draft vm created")
                    KuchiguseCore.onChatShown()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // 构造返回时 VM 里通常已经注入好会话对象，顺手捕获一次
                    KuchiguseCore.onChatVmBuilt(param.thisObject)
                }
            })
            Log.i(TAG, "hooked $clsName.<init> -> chat shown")
        } catch (t: Throwable) {
            Log.e(TAG, "ctor hook failed for $clsName", t)
        }
        try {
            // 切走（进后台、跳别的 Activity）：只收气泡，会话还在，栈不动
            for (m in clazz.declaredMethods.filter { it.name in HIDE_METHODS }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        KuchiguseCore.onChatHidden()
                    }
                })
                Log.i(TAG, "hooked $clsName.${m.name} -> chat stopped")
            }
            // 真退出会话：清栈。
            // 这里**不能**用 ChatPie.onDestroy —— QQ 复用 pie 实例，切换聊天时它也会触发，
            // 照它弹栈会把紧接着要用的会话提前弹掉（实测第二个聊天就没会话了）。草稿 VM
            // 是每页一个，它销毁即这一页结束；KuchiguseCore 里做了防抖，跨过「切聊天」。
            for (m in clazz.declaredMethods.filter { it.name == "onDestroy" }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Log.i(TAG, "chat page closing: draft vm destroyed")
                        KuchiguseCore.onChatPageClosed()
                    }
                })
                Log.i(TAG, "hooked $clsName.onDestroy -> chat closed")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "lifecycle hook failed for $clsName", t)
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
            XposedHelpers.findClass(CHAT_PIE, loader)
        } catch (t: Throwable) {
            Log.w(TAG, "class $CHAT_PIE not found; session tracking limited", t)
            null
        }
        if (pieClass != null) {
            val pieMethods = Reflect.instanceMethods(pieClass)
            // AIO 创建入口。不写死方法名：名字里同时含
            // aio 和 创建/初始化 语义的全部挂上，混淆换了字母也照样命中。
            for (m in pieMethods.filter { isAioCreate(it.name) }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        KuchiguseCore.onChatPieTouched(param.thisObject)
                    }
                })
                Log.i(TAG, "hooked ${m.declaringClass.name}.${m.name} -> aio create")
            }
            // 聊天页 View 生命周期：进聊天页时就把「主人」认下来。子类构造器没法提前
            // 枚举，光靠构造 hook 的话 owner 会一直是 null（会话就归不到任何聊天页上）。
            for (m in pieMethods.filter { isPieLifecycle(it) }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        KuchiguseCore.onChatPieTouched(param.thisObject)
                    }
                })
                Log.i(TAG, "hooked ${m.declaringClass.name}.${m.name} -> pie owner")
            }
            // 这里**不**挂 ChatPie.onDestroy 做弹栈：切换聊天时旧页的 onDestroy 也会
            // 触发，弹栈会把紧接着要用的会话提前弹掉。出栈交给草稿 VM 的 onDestroy。
            XposedBridge.hookAllConstructors(pieClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 就这一次捕获。曾经还补一个 500ms 后的延迟捕获，等 AIO 挂上来，
                    // 但它会在「开聊天后很快退出」时把已经关掉的页面重新压回栈里 ——
                    // 草稿 VM 的清栈 debounce 只有 250ms，早就跑完了。
                    KuchiguseCore.onChatPieCreated(param.thisObject)
                }
            })
            Log.i(TAG, "hooked $CHAT_PIE.<init> -> session capture")
        }

        // 兜底捕获点：AIOParam / AIOSession 的构造与 setter 就是「会话被绑定」的瞬间。
        for (name in arrayOf(AIO_PARAM, AIO_SESSION)) {
            val cls = try {
                XposedHelpers.findClass(name, loader)
            } catch (t: Throwable) {
                Log.w(TAG, "class $name not found", t)
                continue
            }
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    KuchiguseCore.onChatVmBuilt(param.thisObject)
                }
            })
            for (m in Reflect.instanceMethods(cls).filter { it.name.startsWith("set") && it.parameterTypes.size == 1 }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        KuchiguseCore.onChatVmBuilt(param.thisObject)
                    }
                })
                Log.i(TAG, "hooked ${cls.simpleName}.${m.name} -> session capture")
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
            var activityClass: Class<*>? = XposedHelpers.findClass(
                "com.tencent.mobileqq.activity.SplashActivity",
                loader,
            )
            var resumeMethod: java.lang.reflect.Method? = null
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
                XposedBridge.hookMethod(resumeMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        KuchiguseCore.onAppResumed()
                    }
                })
                Log.i(TAG, "hooked SplashActivity.onResume -> app resumed")
            } else {
                Log.w(TAG, "SplashActivity.onResume not found in class hierarchy")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SplashActivity.onResume hook failed", t)
        }
    }

    private fun contactClass(loader: ClassLoader): Class<*> {
        return try {
            XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact", loader)
        } catch (t: Throwable) {
            XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.Contact", loader)
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
