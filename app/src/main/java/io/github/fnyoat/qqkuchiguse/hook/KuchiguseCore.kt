/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.hook

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.fnyoat.qqkuchiguse.FloatController
import io.github.fnyoat.qqkuchiguse.config.CaptionKind
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge
import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig
import io.github.fnyoat.qqkuchiguse.engine.KuchiguseEngine
import io.github.fnyoat.qqkuchiguse.engine.cachedDraw
import io.github.fnyoat.qqkuchiguse.hook.SessionTracker.isGroupChat
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 两个风味（legacy 经典 API / modern 官方 libxposed API）共用的业务逻辑。
 *
 * 这里**不引用任何 Xposed API**，只做纯反射 + 配置/引擎调用，因此同一份代码
 * 既能被 `src/legacy` 的 `IXposedHookLoadPackage` 入口用，也能被 `src/modern`
 * 的 `XposedModule` 入口用。hook 的安装由各风味的入口负责。
 *
 * 当前聊天会话的捕获/还原全部委托给 [SessionTracker]（AIO 创建压栈、聊天页结束
 * 出栈的栈式机制），本对象只负责把会话变化翻译成气泡的显隐。
 */
object KuchiguseCore {

    const val TAG = "KuchiguseEntry"

    /** 外显描述字段的候选名（方法名和字段名大小写不一致，两种都试）。提出来免得每个元素现拼一次。 */
    private val SUMMARY_NAMES = listOf("Summary", "summary")
    private val FACE_NAME_NAMES = listOf("FaceName", "faceName")
    private val CONTENT_NAMES = listOf("Content", "content")

    /** 宿主 Application 引用，Application.onCreate 时缓存，避免每条消息都反射一次。 */
    @Volatile
    var hostApp: Application? = null
        private set

    /** QQ 进程名（主进程为 com.tencent.mobileqq，:MSF 等为子进程）。 */
    @Volatile
    var processName: String = "unknown"
        private set

    /**
     * 当前会话 key（peerUid）。内存里的会话栈是唯一权威。
     *
     * 先让 tracker 自愈一次（栈空时把最近那个 pie 认回来）—— 万一某条 hook 路径没命中，
     * 面板/气泡刷新时也能把当前聊天页认回来，不至于一直显示「未进入会话」。
     *
     * 只认内存里那条栈。以前这里还会读一份落在磁盘上的会话 key，后来那份文件整个删掉了 ——
     * 写它从来没人读，而读它的人（面板每 500ms 刷一次）拿到的要么是自己刚写的空串，要么是
     * 别的进程在别的聊天页写的值，比读不到更糟，还平白给主线程加了文件 IO。
     */
    fun getCurrentPeerUid(): String? {
        SessionTracker.recoverFromScreen()
        return SessionTracker.currentKey
    }

    fun bind(app: Application, process: String) {
        hostApp = app
        processName = process
    }

    fun setProcessName(process: String) {
        processName = process
    }

    fun isMainProcess(): Boolean = processName == ConfigBridge.HOST_PACKAGE

    fun app(): Application? = hostApp

    /**
     * sendMsg 前置：把消息列表里的文本加上口癖。
     *
     * 只就地改消息元素对象，不替换参数本身，所以 legacy 的 `param.args`（数组）
     * 和 modern 的 `chain.getArgs()`（List）都能直接传进来。
     */
    fun applyToSendMsgArgs(args: List<Any?>, contact: Any? = null) {
        if (args.size < 3) return
        val msgElements = args[2] as? java.util.ArrayList<*> ?: return
        // effective()：把号码化之前写进名单的老 uid 条目换成号码，否则判定永远对不上。
        val config = SessionKeys.effective(ConfigBridge.getCachedOrDefault())
        if (!config.enabled) return
        val isGroup = isGroupChat(contact)
        val key = sessionKeyIfNeeded(config, contact)
        if (!config.enabledForSession(key, isGroup)) return
        // 图片外显整块都没开时，applyImageDesc 每个元素都要反射找 pic/face/bubble 子元素、
        // 再读字段，最后却一个字段也不会写。四类全都禁掉就整段跳过，省掉这些纯白跑的反射。
        val anyCaption = CaptionKind.entries.any { config.captionEnabled(it) }
        // 「整条消息只抽一次底句」：同一条消息里同类型共用一次抽签结果，所以缓存建在消息这一层。
        val baseCache = if (anyCaption) HashMap<CaptionKind, String?>() else null
        for (element in msgElements) {
            val textElement = reflectGetTextElement(element)
            if (textElement != null) {
                val content = reflectGetContent(textElement)
                if (!content.isNullOrEmpty()) {
                    val newContent = KuchiguseEngine.process(content, config)
                    if (newContent != null) reflectSetContent(textElement, newContent)
                }
            }
            if (baseCache != null) applyImageDesc(element, config, baseCache)
        }
    }

    /**
     * 给图片 / 表情 / 平底锅写外显描述。
     *
     * 这三种元素没有 textElement，上面的文本分支对它们一点东西都加不上；它们显示的那行字
     * 分别存在 `picElement.summary`、`marketFaceElement` 的 `faceName`、以及
     * `faceBubbleElement` 的 `content` 里。字段和取值方法的名字随 QQ 版本 obfuscate 过，
     * 所以每处都按「方法 → 字段」的顺序试，全部失败就当这个元素不支持，不抛。
     *
     * **底句取 QQ 自己那句默认描述**（`[图片]` / `[表情]` / `[动画表情]`，见
     * [QqPlaceholder]），元素上已经有自己写的说明就用那一句。取不到原描述不是问题：
     * 发送时这些字段本来就是空的，QQ 是渲染时才去资源表取那句字，所以底句必须由模块
     * 从同一张表里取，之前那版读不到就当没底句、结果把底句丢了。
     */
    private fun applyImageDesc(
        element: Any?,
        config: KuchiguseConfig,
        baseCache: MutableMap<CaptionKind, String?>,
    ) {
        if (element == null) return
        val pic = reflectSubElement(element, "PicElement")
        val face = reflectSubElement(element, "MarketFaceElement")
        val bubble = reflectSubElement(element, "FaceBubbleElement")
        if (pic == null && face == null && bubble == null) return
        val ctx = app()
        val hits = buildList {
            if (pic != null) {
                val subType = picSubTypeOf(pic)
                val kind = CaptionKind.forPicSubType(subType)
                if (captionField(pic, kind, config, ctx, baseCache,
                        read = { getStringMember(it, SUMMARY_NAMES) },
                        write = { t, v -> setStringMember(t, SUMMARY_NAMES, v) },
                    )
                ) {
                    add("summary(picSubType=$subType)")
                }
            }
            if (face != null && captionField(face, CaptionKind.EMOJI, config, ctx, baseCache,
                    read = { getStringMember(it, FACE_NAME_NAMES) },
                    write = { t, v -> setGenericMember(t, "faceName", v) },
                )
            ) {
                add("faceName")
            }
            if (bubble != null && captionField(bubble, CaptionKind.FACE_BUBBLE, config, ctx, baseCache,
                    read = { getStringMember(it, CONTENT_NAMES) },
                    write = { t, v -> setGenericMember(t, "content", v) },
                )
            ) {
                add("content")
            }
        }
        if (hits.isEmpty()) {
            Log.d(TAG, "image desc: nothing written on ${element.javaClass.simpleName}")
        } else {
            Log.d(TAG, "image desc -> ${hits.joinToString()}")
        }
    }

    /**
     * 给一个元素算外显描述并写回去。真写了才返回 true（该档关掉、或没抽中一律返回 false，
     * 好让上层日志如实反映「没写」）。
     *
     * [baseCache] 是整条消息共用的底句抽签结果，按类型缓存；这样「随机底句（整条消息）」
     * 那档下，同一条消息里同类型的多个元素拿到的是同一句底句。
     *
     * 底句取元素上已有的说明，没有就用 [QqPlaceholder] 按类型从 QQ 资源表里取那句默认描述。
     * 取不到原描述不是问题：发送时这些字段本来就是空的，QQ 是渲染时才去资源表取字。
     */
    private fun captionField(
        target: Any,
        kind: CaptionKind,
        config: KuchiguseConfig,
        ctx: Context?,
        baseCache: MutableMap<CaptionKind, String?>,
        read: (Any) -> String?,
        write: (Any, String) -> Boolean,
    ): Boolean {
        val origin = runCatching { read(target) }.getOrNull()
        val qqBase = QqPlaceholder.defaultDesc(kind, ctx)
        // 元素上本来就有说明就以它为准，这时候连抽都不抽（抽签会动到口癖的连续抑制计数）。
        // 没抽中（null）也是一次结果，由 cachedDraw 记住、同类型不再重抽。
        val randomBase = if (origin.isNullOrBlank()) {
            baseCache.cachedDraw(kind) { KuchiguseEngine.randomCaptionBase(config, kind, qqBase) }
        } else {
            null
        }
        val base = origin?.takeIf { it.isNotBlank() }
            ?: randomBase?.takeIf { it.isNotBlank() }
            ?: qqBase
        val desc = KuchiguseEngine.imageCaption(config, kind, base, origin)
        if (desc == null) {
            Log.d(TAG, "desc[$kind] origin='$origin' base='$base' -> skipped")
            return false
        }
        val written = runCatching { write(target, desc) }.getOrDefault(false)
        Log.d(
            TAG,
            "desc[$kind] origin='$origin' base='$base' -> " +
                "${if (written) "wrote" else "WRITE FAILED"} '$desc'",
        )
        if (written) {
            Log.d(TAG, "desc[$kind] took '$desc'")
        }
        return written
    }

    /**
     * 读 `picSubType`，用来分图片和表情。只读字段，不改元素。
     */
    private fun picSubTypeOf(pic: Any): Int? = runCatching {
        // 走 [findHierarchyField] 的缓存，别在这里自己再沿继承链扫一遍：每条图片消息
        // 每个元素都要问一次，而这个字段的位置在类加载完就定死了。
        val f = findHierarchyField(pic.javaClass, "picSubType") ?: return null
        (f.get(pic) as? Number)?.toInt()
    }.getOrNull()

    /**
     * 读一个 `String` 成员：先试 `getXxx()`，再试同名字段。读不到返回 null。
     *
     * 和 [setStringMember] 一样要把大小写两种写法都试一遍：QQ 里字段是 `summary`，
     * 方法却是 `getSummary`。
     */
    private fun getStringMember(target: Any, names: List<String>): String? {
        for (n in names) {
            val getter = findHierarchyMethod(target.javaClass, "get${n.replaceFirstChar { it.uppercase() }}")
                ?.takeIf { it.parameterCount == 0 && it.returnType == String::class.java }
            if (getter != null) {
                val v = runCatching {
                    getter.isAccessible = true
                    getter.invoke(target) as String?
                }.getOrNull()
                if (v != null) return v
            }
            val read = runCatching {
                val f = findHierarchyField(target.javaClass, n)
                if (f != null && f.type == String::class.java) {
                    // isAccessible 已在 findHierarchyField 里设好
                    f.get(target) as String?
                } else {
                    null
                }
            }.getOrNull()
            if (read != null) return read
        }
        return null
    }

    /** 取 `element.getXxxElement()`；拿不到就 null，不抛。 */
    private fun reflectSubElement(element: Any, simpleName: String): Any? = runCatching {
        val getter = findHierarchyMethod(element.javaClass, "get$simpleName")
            ?: findHierarchyMethod(element.javaClass, simpleName)
        if (getter == null) return null
        getter.isAccessible = true
        getter.invoke(element)
    }.getOrNull()

    /**
     * 先试 `setXxx(String)`，再试同名字段。写成功返回 true。
     *
     * 字段名和方法名的大小写不一定对得上：QQ 里字段是 `summary`，方法却是 `setSummary`。
     * 所以 `names` 里两种写法都列了，必须挨个试到底，只有真写进去才算成功。
     * 这里原来写成「试一次字段就返回」，第一个名字（`Summary`）没命中字段就白跑，后面那个
     * 真名（`summary`）再也试不到，整条描述就静悄悄地丢了 —— 表现为开关打开但没效果。
     */
    private fun setStringMember(target: Any, names: List<String>, value: String): Boolean {
        for (n in names) {
            val setter = findHierarchyMethod(target.javaClass, "set${n.replaceFirstChar { it.uppercase() }}")
                ?.takeIf { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            if (setter != null && runCatching {
                    setter.isAccessible = true
                    setter.invoke(target, value)
                }.isSuccess
            ) {
                return true
            }
            val written = runCatching {
                val f = findHierarchyField(target.javaClass, n)
                if (f != null && f.type == String::class.java) {
                    f.set(target, value)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (written) return true
        }
        return false
    }

    /**
     * 先试泛型 `set(String, Object)`（QQNT 的元素大多只留这一个 setter），再试
     * `setXxx(String)`，再试字段。写成功返回 true。
     */
    private fun setGenericMember(target: Any, member: String, value: String): Boolean {
        val generic = findHierarchyMethod(target.javaClass, "set")
            ?.takeIf { it.parameterCount == 2 && it.parameterTypes[0] == String::class.java }
        if (generic != null && runCatching {
                generic.isAccessible = true
                generic.invoke(target, member, value)
            }.isSuccess
        ) {
            return true
        }
        return setStringMember(target, listOf(member.replaceFirstChar { it.uppercase() }, member), value)
    }

/** 沿继承链找一个同名字段；`getMethod` 只找 public 的，字段同样要兜底。 */
    private fun findHierarchyField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        val key = "$cls#$name"
        fieldCache[key]?.let { return it }
        if (key in noFieldKeys) return null
        var c: Class<*>? = cls
        var n = 0
        var found: java.lang.reflect.Field? = null
        while (c != null && c != Any::class.java && n < 8) {
            found = c.declaredFields.firstOrNull { it.name == name }
            if (found != null) break
            c = c.superclass
            n++
        }
        // 找到的连同 isAccessible 一起缓存：下面两个调用方原来每次都要重设一遍。
        if (found != null) {
            found.isAccessible = true
            fieldCache[key] = found
        } else {
            // 类加载完字段不会再冒出来，没找到就是没找到，记下来省得每条消息重扫一遍链。
            noFieldKeys.add(key)
        }
        return found
    }

    /**
     * 四份名单里只要有一份非空，就需要解析 key 才知道这个会话算不算被管到。
     *
     * 注意这里只省掉「反射算 key」这一步，省不掉判定：就算四份名单都空着，调用方也照样
     * 要拿 null 去问 [KuchiguseConfig.enabledForSession]。因为空名单在白名单下意味着
     * 「谁都不生效」，不能因为没 key 就当成放行。
     */
    private fun sessionKeyIfNeeded(config: KuchiguseConfig, contact: Any?): String? =
        if (config.hasAnyList()) canonicalSessionKey(contact) else null

    /**
     * 把一段文本按本模块的规则处理一遍，返回**加过口癖的版本**。供其它 Xposed 模块直接
     * 调用，省掉各自再实现一遍概率池、前后缀和会话开关。
     *
     * 约定：
     *  - **原样返回**入参表示这次不改（模块没开、这个会话关了、命中屏蔽词、概率池没抽中），
     *    所以永远非空，调用方不需要判空。
     *  - 只改文本，不碰消息对象和参数数组，写不写回去调用方自己决定。
     *  - 不抛异常，反射/配置异常都吞掉并原样返回，免得从别人的 hook 里炸出去。
     *  - 可从任意线程调用。抽签会记账，所以别拿它当试算工具：反复调用会把平衡往前推。
     *    只想看效果请用设置页的检测器，那条路径不记账。
     *
     * [sessionKey] 传当前会话的 peerUid，拿不到就传 null（等于不按会话过滤）；[isGroup]
     * 拿不准就传 false。[JvmOverloads] 给 Java 调用方留重载，Kotlin 默认参数对 Java 不可见。
     */
    @JvmStatic
    @JvmOverloads
    fun processText(text: String, sessionKey: String? = null, isGroup: Boolean = false): String {
        if (text.isEmpty()) return text
        return runCatching {
            val config = ConfigBridge.getCachedOrDefault()
            if (!config.enabled) return@runCatching text
            if (!SessionKeys.effective(config).enabledForSession(sessionKey, isGroup)) {
                return@runCatching text
            }
            KuchiguseEngine.process(text, config) ?: text
        }.getOrDefault(text)
    }

    /**
     * 同 [processText]，但直接给 QQ 的会话对象（`sendMsg` 第二个参数那个），由本模块
     * 解析出 peerUid。调用方不用自己认会话。
     */
    @JvmStatic
    fun processTextOfContact(text: String, contact: Any?): String {
        if (text.isEmpty()) return text
        return runCatching {
            val config = ConfigBridge.getCachedOrDefault()
            if (!config.enabled) return@runCatching text
            val key = sessionKeyIfNeeded(config, contact)
            processText(text, key, isGroupChat(contact))
        }.getOrDefault(text)
    }

    /**
     * 当前会话的 peerUid，调用方拿不到会话对象时可以用它。
     *
     * 只认内存里那条栈；栈空时返回 null。**不要**缓存这个值：切聊天就会变。
     */
    @JvmStatic
    fun currentSessionKey(): String? = getCurrentPeerUid()

    /** 进程内缓存；仅在用户手动同步时才会真正回到磁盘。 */
    fun readConfig(context: Context): KuchiguseConfig = ConfigBridge.current(context)

    /**
     * 手动同步入口：用户在悬浮面板点「立刻重载配置」时调用，
     * 重新读一次配置文件并刷新进程内缓存。
     */
    @JvmStatic
    fun syncConfigNow(context: Context): KuchiguseConfig = ConfigBridge.reload(context)

    /**
     * 栈顶会话变了。[closed] 为 true 表示栈空 = 确实没有聊天页。
     *
     * 这是气泡显隐的唯一来源：所以只要栈是准的，气泡显示的就是当前聊天，不会有
     * 上一会话的残留；栈一空就立刻收球。
     *
     * 「栈里有聊天页但 key 还没读出来」（[closed] = false 且 [key] = null）时什么都不做 ——
     * AIO 是先构造后填的，key 稍后才会有，这时收球就等于把刚打开的聊天气泡弄没了。
     */
    fun onTrackedSessionChanged(key: String?, closed: Boolean) {
        when {
            closed -> FloatController.onChatDestroyed()
            key != null -> FloatController.onSessionKeyChanged(key)
            else -> Log.i(TAG, "chat page is open but the session key is not filled yet")
        }
    }

    /**
     * 聊天页打开（草稿 VM 构造）。
     *
     * 注意草稿 VM **不等于**人在聊天里：会话列表也会构造一个。所以这里只把 key 交给
     * 气泡，由它决定要不要显示（[FloatController.onChatShown] 里没会话就不显示）。
     * key 不从构造参数里猜 —— 那边字段是混淆的，猜错就会在气泡上留下一个错的会话；
     * 一律等 [SessionTracker] 从 AIO 创建事件里拿。
     */
    fun onChatShown() {
        val app = hostApp ?: return
        // 新聊天页：先把上一页还没执行的关闭作废掉。
        SessionTracker.notePageOpened()
        // AIO 创建通常比草稿 VM 晚一百来毫秒，这一刻会话栈多半还是空的，先把最近
        // 见过的那个 pie 重新认回来（key 是现读的，读到的就是当前会话）。
        SessionTracker.recoverFromScreen()
        FloatController.onChatShown(app, SessionTracker.currentKey)
    }

    /** 草稿 VM 构造完成 / 被调用：VM 构造后通常已被注入会话对象，顺手捕获一次。 */
    fun onChatVmBuilt(vm: Any? = null) {
        SessionTracker.captureFrom(vm)
    }

    /** 聊天页只是被切走（切后台、进别的 Activity），会话还在，不动栈。 */
    fun onChatHidden() {
        FloatController.onChatHidden()
    }

    /**
     * 聊天页结束了（草稿 VM 销毁）。
     *
     * 清栈在 [SessionTracker.schedulePageClose] 里做防抖：切聊天时旧页先销毁、新页紧
     * 跟着构造，这个窗口里新页会登记自己、把这次关闭作废，所以不会把紧接着要用的会话
     * 提前清掉。
     *
     * 这里**不能**改回按 `ChatPie.onDestroy` 弹栈：切换聊天时旧页的 onDestroy 也会触发，
     * 弹栈会把紧接着要用的会话提前弹掉（实测就是「第二个聊天开始就没会话」）。
     */
    fun onChatPageClosed() {
        SessionTracker.schedulePageClose()
    }

    fun onAppResumed() {
        FloatController.onAppResumed(hostApp ?: return)
    }

    fun onHostActivityResumed(activity: android.app.Activity) =
        FloatController.onHostActivityResumed(activity)

    /** ChatPie 构造。归属交给 [onChatPieTouched]。 */
    fun onChatPieCreated(pie: Any?) = onChatPieTouched(pie)

    /**
     * 摸到一个活着的 ChatPie（构造、AIO 创建入口、View 生命周期回调都会走这里）。
     *
     * 归属靠 pie 的构造事件建立 —— 它是唯一能证明「有一个聊天页开着」的事件（AIO 对象的
     * 方法名全被混淆了，认不出来；会话列表也会造 AIO 对象，不能拿来当依据）。
     */
    fun onChatPieTouched(pie: Any?) {
        if (pie == null) return
        SessionTracker.noteOwner(pie)
        SessionTracker.captureFrom(pie)
    }

    /**
     * 唯一 key 空间的取值规则：取 peerUid，**纯数字的 QQ 号优先于 `u_` 开头的 uid**。
     * 发送侧的 `Contact` 和聊天页侧的 `AIOContact` 都走这一个函数，两边才对得上。
     */
    fun canonicalSessionKey(obj: Any?): String? = SessionTracker.peerUidOfStable(obj)

    @Volatile

    private var textElementMethod: Method? = null

    @Volatile
    private var contentMethod: Method? = null

    @Volatile
    private var contentField: Field? = null

    @Volatile
    private var setContentMethod: Method? = null

    /**
     * 已经确认没有 `textElement` 的元素类。图片/表情类每发一条消息都会被问一次
     * [reflectGetTextElement]，不记下来的话每次都白沿继承链找两遍。
     */
    private val noTextElementClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<Class<*>>()

    /**
     * 字段查找的结果，键是 `类名#字段名`。
     *
     * [findHierarchyField] 原来每调一次就沿继承链重扫一遍 `declaredFields`。这个方法
     * 在读/写 `summary`、`title`、`picSubType` 这些成员时都会走到，而每发一条图片消息
     * 就要问好几次，链深的时候一次几百纳秒全是白扫。类加载完字段不会再变，缓存是安全的。
     *
     * 命中和未命中都记：未命中进 [noFieldKeys]，避免每次都为「这个类确实没有这个字段」
     * 重走一遍整条链。
     */
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Field>()

    /** 确认不存在的 `类名#字段名` 组合。 */
    private val noFieldKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 取消息元素对象。
     *
     * `getMethod` 只找 public 方法，QQ 哪个版本把它收成包内可见，这个功能就会**静默**
     * 失效（返回 null，什么都不改，用户只看到口癖没了）。所以按整条继承链找 declared
     * 方法兜底，和内容取值的处理保持一致。
     */
    private fun reflectGetTextElement(element: Any?): Any? {
        if (element == null) return null
        // 先查否定缓存，再试那个全局正缓存：图片元素调正缓存里的方法会抛 IllegalArgumentException，
        // 每次都要付一次异常构造；已知没有 textElement 的类直接在这里短路。
        val cls = element.javaClass
        if (cls in noTextElementClasses) return null
        textElementMethod?.let { m ->
            runCatching { return m.invoke(element) }
        }
        val found = findHierarchyMethod(cls, "getTextElement")
            ?: findHierarchyMethod(cls, "textElement")
        if (found == null) {
            // 没有就是没有：方法/字段不会在类加载后再冒出来，可以安全地记住。
            noTextElementClasses.add(cls)
            return null
        }
        runCatching { found.isAccessible = true }
        textElementMethod = found
        return runCatching { found.invoke(element) }.getOrNull()
    }

    /** 沿继承链找一个无参、同名（忽略大小写）的方法；重载按第一个命中算。 */
    private fun findHierarchyMethod(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        var n = 0
        while (c != null && c != Any::class.java && n < 8) {
            // 先比 parameterCount 再比名字：parameterTypes 每次访问都要克隆一份数组，拿它当谓词
            // 等于给链上每个方法都白克隆一次；parameterCount 只是读一个数，便宜得多。
            c.declaredMethods.firstOrNull { m ->
                m.parameterCount == 0 && m.name.equals(name, ignoreCase = true)
            }?.let { return it }
            c = c.superclass
            n++
        }
        return null
    }

    private fun reflectGetContent(textElement: Any): String? {
        return try {
            if (contentMethod == null) {
                contentMethod = textElement.javaClass.getMethod("getContent").also { it.isAccessible = true }
            }
            contentMethod!!.invoke(textElement) as? String
        } catch (t: Throwable) {
            try {
                if (contentField == null) {
                    contentField = textElement.javaClass.getDeclaredField("content").also { it.isAccessible = true }
                }
                contentField!!.get(textElement) as? String
            } catch (t2: Throwable) {
                null
            }
        }
    }

    private fun reflectSetContent(textElement: Any, value: String) {
        try {
            if (setContentMethod == null) {
                setContentMethod = textElement.javaClass.getMethod("setContent", String::class.java)
                    .also { it.isAccessible = true }
            }
            setContentMethod!!.invoke(textElement, value)
        } catch (t: Throwable) {
            try {
                if (contentField == null) {
                    contentField = textElement.javaClass.getDeclaredField("content").also { it.isAccessible = true }
                }
                contentField!!.set(textElement, value)
            } catch (t2: Throwable) {
                // ignore
            }
        }
    }
}
