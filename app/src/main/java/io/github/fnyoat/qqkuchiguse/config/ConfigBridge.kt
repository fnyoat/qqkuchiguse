/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.config

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * 跨进程配置通道：一个 JSON 文件，没有服务、没有 Provider、没有后台同步。
 *
 * 约束（原始设计，不要改成自动同步）：文件放在**宿主 QQ 自己的** external files dir，
 * QQ 进程读自家目录不要权限，模块 App 写同一路径要 MANAGE_EXTERNAL_STORAGE。hook 侧只在
 * 进程启动时读一次并缓存（[loadIntoCache]），之后每条消息都用缓存、不做磁盘 IO，只有用户
 * 在面板点「立刻重载配置」才重读（[reload]）。
 */
object ConfigBridge {

    private const val TAG = "KuchiguseConfig"

    const val HOST_PACKAGE = "com.tencent.mobileqq"

    /**
     * 名单页的类名。放在这里而不是 modern 的 ActProxy 里：设置页在 main 里，
     * 旧版 QQ 那条路没有 ActProxy，不能让 main 引用只在 modern 里的符号。
     */
    const val LISTS_ACTIVITY = "io.github.fnyoat.qqkuchiguse.SessionListsActivity"

    private const val DIR_NAME = "qqkuchiguse"
    private const val FILE_NAME = "config.json"

    /** 进程内缓存；null 表示还没在本次进程启动时读过盘。 */
    @Volatile
    private var cached: KuchiguseConfig? = null

    /**
     * 当前进程是不是 QQ 宿主进程。
     *
     * 设置页从 QQ 进程里被拉起时为 true。此时 [appFile] 直接用宿主目录，读写都不需要
     * 任何权限，也就不存在「App 看得见、QQ 看不见」这类跨 uid 的错位。
     */
    fun inHostProcess(ctx: Context): Boolean =
        try {
            ctx.packageName == HOST_PACKAGE
        } catch (t: Throwable) {
            false
        }

    /** QQ 进程侧路径：用宿主自己的 external files dir，无需权限。 */
    fun hostFile(hostContext: Context): File? {
        val root = hostContext.getExternalFilesDir(null) ?: return null
        return File(File(root, DIR_NAME), FILE_NAME)
    }

        /**
     * 模块 App 侧候选路径，按可靠性排序。
     *
     * 这台设备上 `/storage/emulated/0/Android/data/<QQ>/` 和 `/storage/self/primary/...`
     * 是同一个文件，但 App 经 FUSE 看到的挂载视图不一样，走 emulated 那条读不到，授权
     * allow 也照样 stat 失败。路径能不能用和权限给没给是两件事，所以把几种写法都列出来
     * 挨个试，谁能读用谁。
     */

    /**
     * 模块 App 侧路径：和 [hostFile] 指向同一份文件，写入需要 all-files access。
     *
     * 但设置页现在**优先跑在 QQ 进程里**（见 [inHostProcess]）：那样的话本函数退化成
     * [hostFile]，属主是 QQ 自己，读写都不需要任何权限 —— 「App 读不到 QQ 的
     * Android/data」这个死结因此彻底消失。保留候选探测只是为了在 QQ 进程外
     * （比如用户从桌面图标直接打开）还能有个能用的落点。
     */
    /**
     * 设置页 / 名单页读写的配置。
     *
     * 这两页只能由 QQ 进程内的 ActProxy 拉起（[io.github.fnyoat.qqkuchiguse.DebugActivity]
     * 解释了为什么不能作为桌面入口），所以 [inHostProcess] 恒为 true，永远走
     * [hostFile] —— QQ 读自己的 external files dir 不需要任何权限。
     *
     * 之前这里还留着一套「猜路径 + 要 all-files access」的流程给独立进程用，那是多余的：
     * 独立进程既没有 QQ 的 ClassLoader 也没有它的 Resources，设置页根本打不开；而且实测
     * 就算授权了，那台设备上照样 stat 失败。现在不猜了，真不在 QQ 进程里就明确报出来。
     */
    fun appFile(ctx: Context): File =
        hostFile(ctx) ?: throw IllegalStateException("not in the QQ process: " + ctx.packageName)

    fun parse(text: String?): KuchiguseConfig =
        if (text.isNullOrBlank()) KuchiguseConfig() else KuchiguseConfig.fromJson(text)

        /**
     * 读配置的三种结果，区别只有「默认值是不是该被当真」。
     *
     * 缺「所有文件访问权限」时 `File.exists()` 会因为看不见 QQ 的 Android/data 目录而
     * 返回 false，于是设置页静悄悄显示一份全是默认值的配置（maxLength=5），而 QQ 进程用
     * 自己的 getExternalFilesDir 读同一份文件、不需权限，实际用的是 10 —— 两边对不上还
     * 查不出来。所以必须把「还没配置过（默认值是对的）」和「读不到（默认值是骗人）」分开。
     */
    sealed interface Load {
        data class Ok(val cfg: KuchiguseConfig) : Load
        /** 文件确实不存在：第一次用，显示默认值没问题。 */
        data object Missing : Load
        /** 文件在（或应该在）但读不了：一般是缺 all-files access。 */
        data class Unreadable(val path: String, val cause: String) : Load
    }

    fun load(file: File?): Load {
        if (file == null) return Load.Missing
        val exists = try { file.exists() } catch (t: Throwable) { false }
        if (exists) {
            return try {
                Load.Ok(parse(file.readText()))
            } catch (t: Throwable) {
                Log.w(TAG, "read failed: ${file.absolutePath}", t)
                Load.Unreadable(file.absolutePath, t.toString())
            }
        }
        // 没看见这个文件：如果连父目录都读不了，那是权限问题而不是「还没配置过」。
        val parentReadable = try {
            file.parentFile?.let { it.isDirectory && it.canRead() } == true
        } catch (t: Throwable) {
            false
        }
        return if (parentReadable) {
            Load.Missing
        } else {
            Log.w(TAG, "config dir not readable: ${file.absolutePath}")
            Load.Unreadable(file.absolutePath, "config dir not readable")
        }
    }
    fun read(file: File?): KuchiguseConfig = when (val r = load(file)) {
        is Load.Ok -> r.cfg
        else -> KuchiguseConfig()
    }

    fun write(file: File?, cfg: KuchiguseConfig): Boolean {
        if (file == null) return false
        return try {
            file.parentFile?.mkdirs()
            // 先写临时文件再 rename：rename 在同一目录内是原子的，这样即使写一半
            // 崩溃 / 掉电，磁盘上要么是旧配置要么是新配置，不会留下被截断的 JSON
            // —— 后者会被 fromJson 静默解析成「全默认」，等于把用户配置清空。
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(cfg.toJson())
            if (!tmp.renameTo(file)) {
                file.writeText(cfg.toJson())
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "write failed: ${file.absolutePath}", t)
            false
        }
    }

    /** 进程启动时调用一次：读盘并缓存。 */
    fun loadIntoCache(hostContext: Context): KuchiguseConfig {
        val cfg = read(hostFile(hostContext))
        cached = cfg
        return cfg
    }

    /** 用户手动同步：重新读盘并刷新缓存。 */
    fun reload(hostContext: Context): KuchiguseConfig = loadIntoCache(hostContext)

    /**
     * 取当前生效配置。**仅供 UI/手动同步**：若缓存为空会兜底读盘并填缓存。
     */
    fun current(hostContext: Context): KuchiguseConfig {
        cached?.let { return it }
        val cfg = read(hostFile(hostContext))
        cached = cfg
        return cfg
    }

    /**
     * 仅供消息发送热路径：直接返回进程内缓存，**绝不读盘**。
     * 缓存为 null 说明冷启动还没跑完，此时直接返回默认配置（等同 disabled）。
     */
    fun getCachedOrDefault(): KuchiguseConfig = cached ?: KuchiguseConfig()

    /** hook 侧（QQ 进程内，用户在悬浮面板改动后）写配置并同步缓存。 */
    fun writeFromHost(hostContext: Context, cfg: KuchiguseConfig): Boolean {
        val ok = write(hostFile(hostContext), cfg)
        if (ok) cached = cfg
        return ok
    }

    /** App 侧（模块设置页）写配置。 */
    fun writeFromApp(ctx: Context, cfg: KuchiguseConfig): Boolean {
        val ok = write(appFile(ctx), cfg)
        // 设置页正常是从 QQ 里那颗齿轮拉起来的，所以它和 hook 在同一个进程、共用同一份
        // 缓存。这时候必须把缓存一起换掉：不然「已保存」弹出来，QQ 那边的 hook 还在用
        // 存盘前那份配置，白名单改了看着就像没生效。非宿主进程写的是另一个进程看不到的
        // 文件，不该碰缓存。
        if (ok && inHostProcess(ctx)) cached = cfg
        return ok
    }

    /** “恢复默认”：删掉配置文件并清缓存，下次读取回到内置默认值。 */
    fun delete(ctx: Context) {
        cached = null
        try {
            appFile(ctx).takeIf { it.exists() }?.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "delete failed", t)
        }
    }
}
