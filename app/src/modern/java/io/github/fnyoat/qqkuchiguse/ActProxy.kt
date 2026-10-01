/*
 * Copyright (C) 2026 fnyoat QQKuchiguse contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Mozilla Public License 2.0 as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package io.github.fnyoat.qqkuchiguse

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge

/**
 * 让 QQ 进程里能真正启动我们的 `SettingsActivity`。
 *
 * 直接把 Intent 指向模块包名会被 MIUI 的 `AppsFilter` 拦掉，日志里表现为
 * `Intent or aInfo is null!` 与 `Activity class {...} does not exist`，最终
 * `result code=-92`：系统在 QQ 进程里查不到这个组件。
 *
 * 做法是把 QQ 进程持有的 `IPackageManager` 换成动态代理。
 * 当 QQ 查询我们的 Activity 时，返回一份伪造的 [ActivityInfo]——字段照抄 QQ 自己的
 * 设置 Activity，但 `name` 换成我们的类名。系统于是按 QQ 的壳去解析，实���实例化的却是
 * 我们的 Activity，跑在 QQ 的进程与 UID 下，跨包检查也就不会触发。
 *
 * 只在 libxposed（modern）下安装：经典 Xposed API 拿不到这条代理路径。
 */
object ActProxy {
    private var sResourcesLoader: Any? = null

    private const val TAG = "QQKuchiguse.ActProxy"

    /** QQ 侧被借来当壳的 Activity，按可用性排序。 */
    private val SHELL_CANDIDATES = arrayOf(
        "com.tencent.mobileqq.activity.QQSettingSettingActivity",
        "com.tencent.mobileqq.activity.QPublicFragmentActivity",
    )

    const val SETTINGS_ACTIVITY = "io.github.fnyoat.qqkuchiguse.SettingsActivity"
    /** 和 [ConfigBridge.LISTS_ACTIVITY] 同一个类名，别处都以那份为准。 */
    const val LISTS_ACTIVITY = ConfigBridge.LISTS_ACTIVITY

    @Volatile
    private var installed = false

    // 名单页也走代理：设置页里那个入口是 Intent(QQ 包, 我们的类名) 起的，
    // 组件落在 QQ 名下，QQ 的 PackageManager 里没有这条记录，不代理就是
    // ActivityNotFoundException（logcat: Unable to find explicit activity class）。
    fun isProxyActivity(className: String?): Boolean =
        className == SETTINGS_ACTIVITY || className == LISTS_ACTIVITY

    /**
     * 替换 QQ 进程的 `PackageManager`，只应生效一次。
     *
     * @param app QQ 的 Application
     * @param hostLoader QQ 的 ClassLoader
     */
    fun install(app: Application, hostLoader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread").invoke(null)
            if (activityThread == null) {
                Log.w(TAG, "install: currentActivityThread is null, skip")
                return
            }
            val spmField = activityThreadClass
                .getDeclaredField("sPackageManager").apply { isAccessible = true }
            val spm = spmField.get(activityThread)
            if (spm == null) {
                Log.w(TAG, "install: sPackageManager is null, skip")
                return
            }

            val iPmInterface = Class.forName("android.content.pm.IPackageManager")
            val pmField = app.packageManager.javaClass
                .getDeclaredField("mPM").apply { isAccessible = true }

            val proxy = Proxy.newProxyInstance(
                iPmInterface.classLoader,
                arrayOf(iPmInterface),
                PackageManagerInvocationHandler(spm, app, hostLoader),
            )

            spmField.set(activityThread, proxy)
            pmField.set(app.packageManager, proxy)
            Log.i(TAG, "install: IPackageManager proxied")

            installActivityManagerProxy()
            installLaunchMessageHook()
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: ${t.message}", t)
        }
    }

    /**
     * 供 `Instrumentation.newActivity` 的 hook 回调使用：基类建不出我们的 Activity 时，
     * 用模块自己的 classloader 造一个。
     *
     * 从 Android 12 起启动是事务化的，改 `LaunchActivityItem.mIntent` 换不回
     * `ActivityClientRecord`（那段补救依赖 hidden 的 `getLaunchingActivity`，SDK>=33
     * 反射被拦）。所以系统按 record 里的 stub 类名去建 Activity，而基类因为这个类不在
     * QQ 的 DexPathList 里必然抛 `ClassNotFoundException`——这个异常就是唯一的接管窗口。
     *
     * 不要再去给宿主 ClassLoader 打补丁让它「加载成功」：那会让异常不再抛出，接管条件
     * 随之消失，系统就会真的建出一个我们无法替换的 stub。之前黑屏正是这个原因。
     */
    fun takeOverActivity(className: String?, cause: Throwable): android.app.Activity? {
        if (!isProxyActivity(className)) return null
        Log.i(TAG, "takeOverActivity: entering for $className")
        return try {
            val c = ActProxy::class.java.classLoader.loadClass(className)
            Log.i(TAG, "takeOverActivity: loaded $c")
            val a = c.getDeclaredConstructor().newInstance() as android.app.Activity
            Log.i(TAG, "takeOverActivity: instantiated $a")
            // 注意：这里绝对不能碰 a.resources。Activity 刚被 new 出来，还没有
            // attach()，ContextThemeWrapper.getResources() 会因为 mBase 为 null
            // 直接抛 NPE。资源注入改在 Instrumentation.callActivityOnCreate 里做，
            // 那时 Activity 已经 attach 完，且早于 onCreate 的第一行 getString。
            Log.i(TAG, "takeOverActivity: built $className")
            a
        } catch (t: Throwable) {
            Log.w(TAG, "takeOverActivity failed: ${t.message}", t)
            null
        }
    }

    /**
     * 把模块 APK 挂进宿主 Activity 的 AssetManager，让 `R.string.*` 能解析。
     * Activity 跑在 QQ 进程时 Resources 是 QQ 的，只认识 QQ 的资源表。
     */
    fun injectResources(activity: android.app.Activity): Boolean {
        val name = activity.javaClass.name
        if (!isProxyActivity(name)) {
            Log.w(TAG, "injectResources: not a proxy activity: $name")
            return false
        }
        val apk = moduleApkPath()
        if (apk == null) {
            Log.w(TAG, "injectResources: module apk path not found")
            return false
        }
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                injectResourcesAboveApi30(activity.resources, apk)
            } else {
                injectResourcesBelowApi30(activity.resources, apk)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "injectResources failed: ${t.message}", t)
            false
        }
    }

    /**
     * 在 [android.app.Instrumentation.callActivityOnCreate] 里给被接管的 Activity 挂上模块资源。
     *
     * Android 12 起 `AssetManager.addAssetPath` 不再让 `getString` 读到新装的资源表，照旧做法
     * 只会抛 `Resources$NotFoundException`，所以必须把 ResourcesProvider 经 ResourcesLoader 挂
     * 到宿主 Resources 上；整条链一律反射（这两个 API 30 的公开类在 stub 里未必带 hidden 成员）。
     * 找方法要沿类继承链往上找：MIUI 换成了 MiuiResources，`addLoaders` 不声明在它本类上。
     *
     * 挂载点必须是这里而不是 [takeOverActivity]：那时 Activity 刚 new 出来、还没 attach()，
     * `activity.resources` 会因 ContextThemeWrapper.mBase 为 null 直接 NPE。
     * 主题不在这里设：provider 不参与 getIdentifier，主题由 SettingsActivity.onCreate 用自己的 R 设。
     */
    fun onProxyActivityCreating(activity: android.app.Activity) {
        if (!isProxyActivity(activity.javaClass.name)) return
        injectResources(activity)
    }

    private fun findDeclaredMethod(
        start: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): java.lang.reflect.Method {
        var k: Class<*>? = start
        while (k != null) {
            try {
                return k.getDeclaredMethod(name, *params)
            } catch (t: NoSuchMethodException) {
                k = k.superclass
            }
        }
        throw NoSuchMethodException("$name not found on ${start.name} hierarchy")
    }

    private fun injectResourcesAboveApi30(res: android.content.res.Resources, apk: String): Boolean {
        // 这两个类是 boot classpath 上的 framework 隐藏类。必须显式用 boot
        // classloader 去查：模块自己的 ClassLoader（LSPosed 的 k1）的 parent 链
        // 里没有 boot classpath，直接 Class.forName 会 ClassNotFoundException。
        val boot = android.content.res.Resources::class.java.classLoader
        val providerClass = Class.forName("android.content.res.loader.ResourcesProvider", true, boot)
        val loaderClass = Class.forName("android.content.res.loader.ResourcesLoader", true, boot)
        if (sResourcesLoader == null) {
            val pfd = android.os.ParcelFileDescriptor.open(
                java.io.File(apk), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            try {
                val provider = providerClass
                    .getDeclaredMethod("loadFromApk", android.os.ParcelFileDescriptor::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, pfd)
                sResourcesLoader = loaderClass.getDeclaredConstructor().newInstance().also { loader ->
                    loaderClass
                        .getDeclaredMethod("addProvider", providerClass)
                        .apply { isAccessible = true }
                        .invoke(loader, provider)
                }
            } finally {
                pfd.close()
            }
        }
        val loader = sResourcesLoader ?: return false
        return try {
            // addLoaders 是变参 ResourcesLoader...，要传数组，元素类型用
            // boot 上拿到的 loaderClass，不能用 interfaces[0] 猜。
            val arr = java.lang.reflect.Array.newInstance(loaderClass, 1)
            java.lang.reflect.Array.set(arr, 0, loader)
            // res.addLoaders(...) 里 res 的声明类型是 Resources，所以方法解析落在
            // android.content.res.Resources 上。反射时必须同样以 Resources 为基准去查，
            // 不能用 res.javaClass（运行时实现类 ResourcesImpl / MiuiResources
            // 都不自己声明 addLoaders），否则任何设备上都会 NoSuchMethodException。
            val m = try {
                android.content.res.Resources::class.java
                    .getDeclaredMethod("addLoaders", arr.javaClass)
            } catch (t: NoSuchMethodException) {
                findDeclaredMethod(res.javaClass, "addLoaders", arr.javaClass)
            }
            m.apply { isAccessible = true }.invoke(res, arr)
            Log.i(TAG, "injectResources: ResourcesLoader added, apk=$apk")
            true
        } catch (t: IllegalArgumentException) {
            // ResourcesImpl 没注册在 ResourcesManager 上时 addLoaders 会拒绝，退回老办法。
            Log.w(TAG, "addLoaders rejected: ${t.message}, fallback")
            injectResourcesBelowApi30(res, apk)
        } catch (t: Throwable) {
            Log.w(TAG, "addLoaders failed: ${t.message}", t)
            false
        }
    }

    private fun injectResourcesBelowApi30(res: android.content.res.Resources, apk: String): Boolean {
        val add = Class.forName("android.content.res.AssetManager")
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        add.invoke(res.assets, apk)
        Log.i(TAG, "injectResources: addAssetPath done, apk=$apk")
        return true
    }

    private fun moduleApkPath(): String? {
        val all = mutableListOf<String>()
        var cl: ClassLoader? = ActProxy::class.java.classLoader
        while (cl != null) {
            try {
                val pl = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList").apply { isAccessible = true }
                    .get(cl) as? List<*>
                if (pl == null) {
                    Log.i(TAG, "moduleApkPath: no pathList on ${cl.javaClass.name}")
                } else {
                    for (e in pl) {
                        val p = try {
                            e?.javaClass?.getDeclaredField("path")
                                ?.apply { isAccessible = true }?.get(e) as? String
                        } catch (t: Throwable) {
                            null
                        }
                        if (p != null) all.add(p)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "moduleApkPath: pathList on ${cl.javaClass.name}: ${t.message}")
            }
            val next = cl.parent
            if (next === cl) break
            cl = next
        }
        Log.i(TAG, "moduleApkPath: candidates=${all.joinToString()}")
        val fromLoader = all.firstOrNull { it.contains("qqkuchiguse") && it.endsWith(".apk") }
            ?: all.firstOrNull { it.endsWith(".apk") }
        if (fromLoader != null) return fromLoader

        // LSPosed 的模块 ClassLoader（这里是 k1）不是 BaseDexClassLoader，模块 APK
        // 也不在 parent 链的 pathList 上，反射拿不到。依次兜底，全程不查
        // PackageManager，也就绕开了包可见性与 HMA 的拦截。
        val fromToString = findApkInClassLoaders(ActProxy::class.java.classLoader)
        if (fromToString != null) return fromToString

        // 模块 dex 一定被映射进了本进程，/proc/self/maps 里能看到路径。
        // 注意 File.readLines() 在读取被拒时返回空列表而不抛异常，必须用
        // FileInputStream 才能拿到真实错误。
        val mapped = try {
            java.io.FileInputStream("/proc/self/maps").use { fis ->
                fis.bufferedReader().readLines()
            }.mapNotNull { line ->
                line.substringAfterLast(' ').trim().takeIf { it.isNotEmpty() }
            }.filter { it.contains("qqkuchiguse") }
        } catch (t: Throwable) {
            Log.w(TAG, "moduleApkPath: /proc/self/maps failed: ${t.message}")
            emptyList()
        }
        Log.i(TAG, "moduleApkPath: mapped=${mapped.joinToString()}")
        mapped.firstOrNull { it.endsWith(".apk") && java.io.File(it).canRead() }
            ?.let { return it }
        mapped.firstOrNull { it.endsWith(".apk") }?.let { return it }

        // 最后再试一次包查询：Android 11+ 的包可见性通常会让这里返回 null，
        // 但部分环境下 LSPosed 已经放行，能兜到就先用。
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.app.Application
            val ai = app?.packageManager
                ?.getApplicationInfo(MODULE_PKG, 0)
            Log.i(TAG, "moduleApkPath: pm sourceDir=${ai?.sourceDir}")
            ai?.sourceDir
        } catch (t: Throwable) {
            Log.w(TAG, "moduleApkPath: pm failed: ${t.message}")
            null
        }
    }

    /**
     * ART 的 [dalvik.system.PathClassLoader.toString] 形如
     * `dalvik.system.PathClassLoader[/data/app/~~xx/pkg/base.apk]`，直接带 APK 路径。
     * LSPosed 的模块加载器会把真正的加载器包在字段里，所以也顺带扫一层字段。
     */
    private fun findApkInClassLoaders(start: ClassLoader?): String? {
        var cl: ClassLoader? = start
        var depth = 0
        while (cl != null && depth < 8) {
            depth++
            val text = try {
                cl.toString()
            } catch (t: Throwable) {
                ""
            }
            Log.i(TAG, "moduleApkPath: cl[$depth]=${cl.javaClass.name} toString=$text")
            APK_IN_PATH.find(text)?.value?.let { return it }

            var k: Class<*>? = cl.javaClass
            var fdepth = 0
            while (k != null && fdepth < 4) {
                fdepth++
                for (f in k.declaredFields) {
                    if (f.type != String::class.java && !ClassLoader::class.java.isAssignableFrom(f.type)) {
                        continue
                    }
                    val v = try {
                        f.apply { isAccessible = true }.get(cl)
                    } catch (t: Throwable) {
                        null
                    }
                    when (v) {
                        is String -> {
                            Log.i(TAG, "moduleApkPath: cl[$depth].${k.simpleName}.${f.name}=$v")
                            APK_IN_PATH.find(v)?.value?.let { return it }
                        }
                        is ClassLoader -> {
                            val s = try {
                                v.toString()
                            } catch (t: Throwable) {
                                ""
                            }
                            Log.i(TAG, "moduleApkPath: cl[$depth].${k.simpleName}.${f.name}=$s")
                            APK_IN_PATH.find(s)?.value?.let { return it }
                        }
                    }
                }
                k = k.superclass
            }
            val next = cl.parent
            if (next === cl) break
            cl = next
        }
        return null
    }

    /**
     * system_server 回传 LAUNCH_ACTIVITY 后，在 QQ 主线程上把 Intent 换回我们的。
     *
     * 注意 callback 字段在 [android.os.Handler] 上，不在 MessageQueue 上；而且
     * LAUNCH_ACTIVITY 从 Android 10 起由 `mTransactionHandler`（ClientTransactionHandler）
     * 处理，不再走老的 `mH`。所以这里把 ActivityThread 里的 Handler 都挂一遍，
     * 不去猜这个版本用哪个。
     */
    private fun installActivityManagerProxy() {
        try {
            val singletonClass = Class.forName("android.util.Singleton")
            val mInstance = singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }

            // 新版本没有 ActivityManagerNative 了，ActivityTaskManager 才是持有 IActivityManager 的地方。
            // 先 get() 一次把实例建出来，再替换 mInstance。
            val singleton = Class.forName("android.app.ActivityTaskManager")
                .getDeclaredField("IActivityTaskManagerSingleton")
                .apply { isAccessible = true }.get(null) ?: return
            singletonClass.getMethod("get").invoke(singleton)
            val target = mInstance.get(singleton) ?: return

            val iAmInterface = Class.forName("android.app.IActivityTaskManager")
            val proxy = Proxy.newProxyInstance(
                ActProxy::class.java.classLoader,
                arrayOf(iAmInterface),
                ActivityManagerInvocationHandler(target),
            )
            mInstance.set(singleton, proxy)
            Log.i(TAG, "install: IActivityTaskManager proxied")
        } catch (t: Throwable) {
            Log.w(TAG, "IActivityManager proxy failed: ${t.message}", t)
        }
    }

    private class ActivityManagerInvocationHandler(private val target: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "startActivity") {
                val a = args ?: return method.invoke(target)
                val intent = a.firstOrNull { it is Intent } as? Intent
                val component = intent?.component
                if (component != null &&
                    component.packageName == ConfigBridge.HOST_PACKAGE &&
                    isProxyActivity(component.className)
                ) {
                    val wrapper = Intent().setClassName(ConfigBridge.HOST_PACKAGE, STUB_ACTIVITY)
                    wrapper.putExtra(EXTRA_REAL_INTENT, intent)
                    // 换掉传给 system_server 的那个 Intent
                    for (i in a.indices) {
                        if (a[i] === intent) {
                            @Suppress("UNCHECKED_CAST")
                            (a as Array<Any?>)[i] = wrapper
                            break
                        }
                    }
                    Log.i(TAG, "startActivity: swapped to stub $STUB_ACTIVITY")
                }
            }
            return method.invoke(target, *(args ?: emptyArray()))
        }
    }

    /**
     * system_server 回传 LAUNCH_ACTIVITY 后，在 QQ 主线程上把 Intent 换回我们的。
     *
     * 注意 callback 字段在 [android.os.Handler] 上，不在 MessageQueue 上；而且
     * LAUNCH_ACTIVITY 从 Android 10 起由 `mTransactionHandler`（ClientTransactionHandler）
     * 处理，不再走老的 `mH`。所以这里把 ActivityThread 里的 Handler 都挂一遍，
     * 不去猜这个版本用哪个。
     */
    private fun installLaunchMessageHook() {
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread").invoke(null) ?: return
            val callbackField = Class.forName("android.os.Handler")
                .getDeclaredField("mCallback").apply { isAccessible = true }

            var armed = 0
            for (f in activityThread.javaClass.declaredFields) {
                if (!android.os.Handler::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val handler = try {
                    f.get(activityThread) as? android.os.Handler
                } catch (t: Throwable) {
                    null
                } ?: continue
                val current = callbackField.get(handler) as? android.os.Handler.Callback
                // mCallback 是接口字段，Field.set 不接受普通对象，必须塞接口的动态代理
                if (current != null &&
                    current.javaClass.name.contains("LaunchCallbackInvocationHandler")
                ) continue
                val wrapped = Proxy.newProxyInstance(
                    ActProxy::class.java.classLoader,
                    arrayOf(android.os.Handler.Callback::class.java),
                    LaunchCallbackInvocationHandler(current),
                )
                callbackField.set(handler, wrapped)
                armed++
                Log.i(TAG, "armed handler field: ${f.name}")
            }
            Log.i(TAG, "install: LAUNCH_ACTIVITY hook armed on $armed handler(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "launch message hook failed: ${t.message}", t)
        }
    }

    private class LaunchCallbackInvocationHandler(private val target: Any?) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val msg = args?.firstOrNull { it is android.os.Message } as? android.os.Message
            // 100 = LAUNCH_ACTIVITY，159 = EXECUTE_TRANSACTION
            if (msg != null && (msg.what == 100 || msg.what == 159)) {
                try {
                    if (msg.what == 100) restoreFromLaunchActivity(msg.obj)
                    else restoreFromExecuteTransaction(msg.obj)
                } catch (t: Throwable) {
                    Log.w(TAG, "restore intent failed: ${t.message}", t)
                }
            }
            @Suppress("UNCHECKED_CAST")
            return if (target != null) {
                method.invoke(target, *(args ?: emptyArray()))
            } else {
                when (method.name) {
                    "handleMessage" -> false
                    else -> null
                }
            }
        }

        // 字段名各版本不一样（intent / mIntent），拿不到就安静跳过：
        // 这是每个 Activity 启动都会走到的路径，不该刷警告。
        private fun intentFieldOf(item: Any): java.lang.reflect.Field? {
            for (name in arrayOf("mIntent", "intent")) {
                val f = try {
                    item.javaClass.getDeclaredField(name).apply { isAccessible = true }
                } catch (t: Throwable) {
                    null
                } ?: continue
                if (Intent::class.java.isAssignableFrom(f.type)) return f
            }
            return null
        }

        private fun restoreFromLaunchActivity(record: Any?) {
            if (record == null) return
            val intentField = intentFieldOf(record) ?: return
            val wrapper = intentField.get(record) as? Intent ?: return
            restore(wrapper, intentField, record)
        }

        private fun restoreFromExecuteTransaction(transaction: Any?) {
            if (transaction == null) return
            val getCallbacks = try {
                Class.forName("android.app.servertransaction.ClientTransaction")
                    .getDeclaredMethod("getCallbacks").apply { isAccessible = true }
            } catch (t: Throwable) {
                return
            }
            val items = try {
                getCallbacks.invoke(transaction) as? List<*>
            } catch (t: Throwable) {
                null
            } ?: return
            for (item in items) {
                if (item == null) continue
                if (!item.javaClass.name.contains("LaunchActivityItem")) continue
                val mIntent = intentFieldOf(item) ?: continue
                val wrapper = mIntent.get(item) as? Intent ?: continue
                restore(wrapper, mIntent, item)
            }
        }
        /**
         * 把 wrapper 里的真 Intent 换回去，并清掉标记。
         *
         * 判断「是不是我们的 Intent」只能看 component，不能用 hasExtra()：
         * hasExtra() 会走 BaseBundle.containsKey()，那会把 extras 立刻 unparcel。
         * 这个回调对**每一个**启动的 Activity 都会跑，于是所有带 Parcelable 的
         * Intent 都被提前反序列化一次，等 QQ 自己再读的时候 classloader 已经不
         * 对了，图片查看器（RFWLayerInitBean）和资料卡（AllInOne）直接
         * ClassNotFoundException / BadParcelableException 起不来。
         * component 是壳 Intent 上直接写死的字段，读它不碰 extras。
         */
        private fun restore(wrapper: Intent, field: java.lang.reflect.Field, holder: Any) {
            if (wrapper.component?.className != STUB_ACTIVITY) return
            @Suppress("DEPRECATION")
            val real = wrapper.getParcelableExtra<Intent>(EXTRA_REAL_INTENT) ?: return
            val extrasField = Intent::class.java.getDeclaredField("mExtras").apply { isAccessible = true }
            (extrasField.get(wrapper) as? Bundle)?.remove(EXTRA_REAL_INTENT)
            field.set(holder, real)
            Log.i(TAG, "restored real intent: ${real.component}")
        }
    }

    /** QQ 里真实存在、且不需要参数就能启动的 Activity，仅作为 system_server 的目标。 */
    private const val STUB_ACTIVITY = "com.tencent.mobileqq.activity.photo.CameraPreviewActivity"
    private const val MODULE_PKG = "io.github.fnyoat.qqkuchiguse"
    private val APK_IN_PATH = Regex("(/[^\\s\\[\\]]*\\.apk)")

    private const val EXTRA_REAL_INTENT = "io.github.fnyoat.qqkuchiguse.ActProxy.REAL_INTENT"

    private class PackageManagerInvocationHandler(
        private val target: Any,
        private val app: Application,
        private val hostLoader: ClassLoader,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            // 原型：ActivityInfo getActivityInfo(in ComponentName className, int flags, int userId)
            if (method.name == "getActivityInfo") {
                val real = method.invoke(target, *a) as ActivityInfo?
                if (real != null) return real

                val component = a.getOrNull(0) as? ComponentName ?: return null
                // 只为 QQ 包名下、属于我们的 Activity 造假
                if (component.packageName != ConfigBridge.HOST_PACKAGE) return null
                if (!isProxyActivity(component.className)) return null
                return makeProxyActivityInfo(component.className, a.getOrNull(1), app, hostLoader)
            }
            return method.invoke(target, *a)
        }
    }

    /**
     * 造一份 `ActivityInfo`：沿用 QQ 壳的信息，只把类名换成我们的。
     *
     * 主题保留 QQ 壳的，不换成模块自己的：模块的资源 ID 在 QQ 的 Resources 里解析不了，
     * 换主题就得把整套资源注入进 QQ 进程，那不再是「轻量」。设置页全用原生 View，
     * 套在 QQ 的壳主题里显示正常。
     */
    private fun makeProxyActivityInfo(
        className: String,
        flagsArg: Any?,
        app: Application,
        hostLoader: ClassLoader,
    ): ActivityInfo? {
        return try {
            // 用模块自己的 loader 确认类在：QQ 的 loader 看不到模块类，
            // 那正是 installClassLoaderBridge 要补的缺口。
            ActProxy::class.java.classLoader.loadClass(className)

            val flags = ((flagsArg as? Number)?.toLong() ?: 0L).toInt()
            var proto: ActivityInfo? = null
            var last: PackageManager.NameNotFoundException? = null
            var shellUsed = ""
            for (shell in SHELL_CANDIDATES) {
                try {
                    proto = app.packageManager
                        .getActivityInfo(ComponentName(ConfigBridge.HOST_PACKAGE, shell), flags)
                    shellUsed = shell
                    break
                } catch (e: PackageManager.NameNotFoundException) {
                    last = e
                }
            }
            val ai = proto ?: throw IllegalStateException(
                "no shell activity found, are we in the host?", last,
            )

            ai.targetActivity = null
            ai.taskAffinity = null
            ai.descriptionRes = 0
            ai.name = className
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ai.splitName = null
            }
            Log.i(TAG, "makeProxyActivityInfo: $className via $shellUsed")
            ai
        } catch (t: Throwable) {
            Log.w(TAG, "makeProxyActivityInfo failed: ${t.message}", t)
            null
        }
    }
}
