/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque

/**
 * 当前聊天会话的捕获与还原：一条按聊天页维护的栈，栈顶就是当前所在会话。
 *
 * 几条关键取舍，每一个都对应一个具体的坑：
 *
 * 1. **栈里存聊天页（ChatPie）而不是会话快照，contact 和 key 每次现读。** AIO 数据对象
 *    先构造后填，`AIOParam` 构造完里面还是空壳；捕获时就缓存 key 的话面板会一直显示
 *    「未进入会话」。
 * 2. **不能用销毁黑名单，也不能靠 `onDestroy` 判生死。** 切换聊天时旧页的 `onDestroy`
 *    也会触发，照它弹栈会把紧接着要用的会话提前弹掉。判据是**草稿 VM**
 *    （`InputDraftVMDelegate`），它每页新建/销毁一个。
 * 3. **ChatPie 的方法名全被混淆，也不是 View**，按名字猜的 hook 和 View 树都用不上，
 *    只能 hook 它的构造器。
 * 4. **key 要在 AIO 对象上读，不能在 pie 上读**：pie 只是「哪个聊天页」的标记，contact
 *    同样是先构造后填，从 pie 顺藤摸瓜在第二个及以后的聊天里读到的永远是空的。
 *
 * 纯反射，不引用 Xposed API，所以两个风味共用这一份。
 */
object SessionTracker {

    private const val TAG = "KuchiguseSession"

    private const val CHAT_PIE = "com.tencent.aio.base.chat.ChatPie"
    private const val AIO_PARAM = "com.tencent.aio.data.AIOParam"
    private const val AIO_SESSION = "com.tencent.aio.data.AIOSession"
    private const val AIO_CONTACT = "com.tencent.aio.data.AIOContact"

    /**
     * 栈深上限。
     *
     * 现在**没有任何单条弹栈**了（出栈只在聊天页真的结束时整栈清空），所以切一次聊天就
     * 多一项 —— 这个上限实际上是个滑动窗口：超过就把最老的挤掉。栈顶永远是最新那个 pie，
     * 所以 key 不会错，`depth` 只会在日志里一直涨。
     */
    private const val MAX_DEPTH = 8

    /**
     * 现读的短缓存。面板每 500ms 刷一次，而现读要顺着 pie 反射三层，所以缓存一下，
     * 够用又不至于每次都反射。
     */
    private const val RESOLVE_MEMO_MS = 200L

    /**
     * 捕获之后再过这些时间点复查一次 key（AIO 就是「先构造后填」）。
     *
     * 最后一个点拉到 2s：常规捕获已经不再每次都强制重反射（见 [captureFrom]），所以
     * 「很晚才填上、期间一个事件都没有」这种情况只能靠这条定时复查兜住。
     */
    private val RESOLVE_DELAYS = longArrayOf(100L, 350L, 900L, 2000L)

    /**
     * 聊天页结束后的清栈延迟。
     *
     * 必须跨过「切聊天」：QQ 复用 pie 实例，切换时旧页的草稿 VM 会先销毁、新页的草稿
     * VM 紧接着才构造，这个窗口里新页会登记自己、把这次关闭作废。
     */
    private const val PAGE_CLOSE_DELAY_MS = 250L

    /**
     * 每开一个聊天页 +1，用来作废上一页还没执行的关闭。
     *
     * hook 回调不一定跑在主线程，自增也要原子，所以用 AtomicLong 而不是 volatile var。
     */
    private val pageGeneration = java.util.concurrent.atomic.AtomicLong()

    /**
     * 现在确实有一个聊天页开着吗（草稿 VM 构造过、且还没到关闭 debounce）。
     *
     * 这是**准入条件**，不是优化：会话列表滚动时会造一大堆 AIO 对象，光靠「最近见过 pie」
     * 认人就会把它们算到上一个聊天页上，面板和气泡会显示一个错的会话。所以只有 pie 自身
     * 的构造事件（能证明有聊天页新建）和「有草稿 VM 活着」这两种情况才认 AIO 事件。
     */
    @Volatile
    private var pageAlive = false

    /** 栈里一项：只记归属的聊天页。contact / key 现读，所以这里不用也不能存快照。 */
    private class Entry(val owner: Any) {
        /**
         * 最近一次带来「有新会话了」的那个 AIO 对象（AIOParam / AIOSession）。
         *
         * [owner]（ChatPie）只是用来算「这是不是同一个聊天页」——它每个聊天页新建一个，
         * 而 contact 和 key 是**在 AIO 对象上**的：实测从 pie 上顺藤摸瓜常常读到空的，
         * 真正的 uid 在触发捕获的那个 AIO 对象上。所以取 key 必须优先走这里。
         */
        @Volatile
        var source: Any? = null
    }

    /**
     * 每个类只解析一次的取值器。会话捕获是低频事件，但发送侧每条消息都会走
     * [peerUidOf]，所以 getter / field 列表必须缓存，不能每次调用都重建。
     */
    private class Extractor(
        val all: List<Field>,
        val getters: List<Method>,
        val fields: List<Field>,
        val typeGetters: List<Method>,
        val typeFields: List<Field>,
        val intFields: List<Field>,
        /**
         * 名字像 QQ 号、类型是数字的取值口。
         *
         * QQ 号在 AIOContact 上是 `long` 字段，不是 String —— [readAll] 只读 String，
         * 所以它从来没被读到过，最后只能退回 `u_` 开头的 uid，名单里于是全是 uid。
         * 数字这条路必须按名字筛：int/long 字段里还有 chatType、seq、时间戳这些，
         * 不筛就会把它们的值当成会话标识。
         */
        val numGetters: List<Method>,
        val numFields: List<Field>,
    )

    private val extractorCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Extractor>()

    private val lock = Any()

/**
 * 一次会话解析的完整结果，作为一个整体发布。
 *
 * 字段必须配套使用：[contact] 是从 [owner]／[source] 上读出来的，[key] 又是从
 * [contact] 上读出来的。拆开存就可能在并发下被读成不相干的两半。
 */
private class Memo(
    val owner: Any?,
    val source: Any?,
    val contact: Any?,
    val key: String?,
    val at: Long,
)
    private val stack = ArrayDeque<Entry>()

    /** 最新一次见到（构造或回调里摸到）的 ChatPie。 */
    @Volatile
    private var newestOwner: Any? = null

    /**
     * 会话短缓存。**一个不可变的整体**，不是五个分开的字段。
     *
     * 这几个值必须互相配对：memoOwner 是这个 pie、memoContact 就是这个 pie 的 AIO 对象、
     * memoKey 就是从那个对象上读出来的 key。分成五个 volatile 的话，写的时候是五次独立
     * 写、读的时候是五次独立读，中间被别的线程插进来就会拼出「A 的 pie 配 B 的 key」——
     * 那会让面板短暂地显示、甚至开关掉一个完全不相干的会话。合成一个对象之后一次
     * volatile 读就拿到自洽的一对，顺带还少了四次 volatile 读。
     */
    @Volatile
    private var memo: Memo? = null

    @Volatile
    private var notifiedKey: String? = null

    @Volatile
    private var notifiedClosed = true

    /** AIOContact 的字段布局只解释一次，避免刷屏。 */
    @Volatile
    private var dumped = false

    /** 「当前没有聊天页」的日志只打前几条，会话列表那些 AIO 创建很吵。 */
    @Volatile
    private var noChatPageLogs = 0

    /** 「没有聊天页开着，AIO 事件被丢掉的日志」单独计数，别把旧消息的额度用光。 */
    @Volatile
    private var gateDropLogs = 0

    /** 当前栈顶聊天页。 */
    val currentOwner: Any? get() = synchronized(lock) { stack.peek()?.owner }

    /** 栈顶最近一次捕获到的 AIO 对象 —— key 在它身上。 */
    val currentSource: Any? get() = synchronized(lock) { stack.peek()?.source }

    /** 当前会话联系人（从栈顶聊天页现读）。 */
    val currentContact: Any? get() = resolveMemoized().first

    /**
     * 当前会话 key（peerUid），会话级开关和气泡配色都用它。
     *
     * 现读：从栈顶聊天页顺藤摸瓜取 AIOContact 再取 uid。AIO 是先构造后填的，所以这里
     * 读到的永远是**当下**已经填好的值，不会停在那个空壳上。
     */
    val currentKey: String? get() = resolveMemoized().second

    /** 栈空 == 确定当前没有聊天页。 */
    val sessionClosed: Boolean get() = synchronized(lock) { stack.isEmpty() }

    val depth: Int get() = synchronized(lock) { stack.size }

    /**
     * AIO 创建 / 绑定完成。[host] 是被 hook 到的那个对象（ChatPie、AIOParam、AIOSession
     * 或草稿 VM 都行）：ChatPie 直接当归属，其它对象用来触发「现在该看一眼聊天页」。
     */
    fun captureFrom(host: Any?) {
        ensureStats()
        val t0 = SystemClock.elapsedRealtimeNanos()
        statCaptures.increment()
        try {
            // 「现在有没有聊天页开着」是**进会话时**就定下来的状态，不是每个事件都要重新
            // 回答一遍的问题：这里只读那个标志位，归属直接用认好的那个 pie。
            if (!pageAlive) {
                // 会话列表滚动时会造一大堆 AIO 对象，而 newestOwner 永远是上一次那个 pie ——
                // 认下去就会把列表项的会话显示到气泡上。
                statGated.increment()
                if (gateDropLogs < 5) {
                    gateDropLogs++
                    Log.i(TAG, "capture: no chat page open, ignored")
                }
                return
            }
            val owner = newestOwner ?: run {
                if (noChatPageLogs < 5) {
                    noChatPageLogs++
                    Log.i(TAG, "capture: no chat page known yet, ignored")
                }
                return
            }
            var added = false
            var sourceChanged = false
            synchronized(lock) {
                val top = stack.peek()
                // 同一个 pie 重复触发（构造 + setter 各来一次）不入栈。QQ 切聊天时会复用 pie
                // 实例，所以「同一个 owner」只说明「还是同一个 pie」，不代表是同一页；换页
                // 靠 [notePageOpened] 取消待执行的关闭，和草稿 VM 的新代次。
                if (top == null || top.owner !== owner) {
                    while (stack.size >= MAX_DEPTH) stack.removeFirst()
                    stack.addLast(Entry(owner))
                    added = true
                }
                // 把这次触发带来的 AIO 对象记到栈顶上：key 在它身上，不在 pie 身上。
                if (host != null && host !== owner) {
                    val entry = stack.peek()
                    if (entry != null && entry.source !== host) {
                        entry.source = host
                        sourceChanged = true
                    }
                }
            }
            if (added) {
                Log.i(
                    TAG,
                    "capture: ${owner.javaClass.simpleName}@${hex(owner)}" +
                        " depth=$depth source=${currentSource?.javaClass?.simpleName}"
                )
            }
            // 只有「栈顶换了 / AIO 对象换了 / 缓存过期」才真的重反射一次。
            if (added || sourceChanged || memoExpired()) {
                val t1 = SystemClock.elapsedRealtimeNanos()
                notifyIfChanged(forceResolve = true)
                statResolveNanos.add(SystemClock.elapsedRealtimeNanos() - t1)
                statForced.increment()
            }
            if (added || resolveMemoized().second == null) scheduleResolve(owner)
        } finally {
            statCaptureNanos.add(SystemClock.elapsedRealtimeNanos() - t0)
        }
    }

    /** 短缓存是不是已经过期 —— 用来给「要不要重反射」一个统一判据。 */
    private fun memoExpired(): Boolean {
        val at = memo?.at ?: return true
        return SystemClock.uptimeMillis() - at >= RESOLVE_MEMO_MS
    }

    /** 进程/宿主异常兜底：清空整栈，回到「没有会话」。 */
    fun reset() {
        pageAlive = false
        synchronized(lock) {
            stack.clear()
            newestOwner = null
            invalidateMemoLocked()
        }
        notifyIfChanged(forceResolve = true)
        Log.i(TAG, "reset: stack cleared")
    }

    /**
     * 记住摸到的 ChatPie —— **进会话的唯一检测点**。
     *
     * 认人只能靠 ChatPie 的构造器 hook：它的方法名被 QQ 全混淆了，`aio create`、View
     * 生命周期那两组按名匹配的 hook 一条都装不上，而 ChatPie 本身又不是 View（所以也
     * 不能靠 attach 回调或 View 树找回它）。
     *
     * 归属和「门开着」在这里一起写。以前开门散在三个地方，其中 [captureFrom] 那条只开
     * [pageAlive]、却不记 owner，于是会出现「门开着、归属却还指上一页」—— 表现出来就是
     * 气泡挂到错误的会话上。其它地方只管读这两个状态，不重新判断有没有聊天页。
     */
    fun noteOwner(owner: Any?) {
        if (owner == null || !isPie(owner)) return
        newestOwner = owner
        pageAlive = true
    }

    /**
     * 有一个新的聊天页打开了。
     *
     * 草稿 VM 是**每个聊天页一个**的，进聊天必然新建一个；所以它是「聊天页真的换了一
     * 个」最可靠的信号，用来取消上一次还没来得及执行的关闭。
     */
    fun notePageOpened() {
        pageAlive = true
        pageGeneration.incrementAndGet()
    }

    /**
     * 聊天页真的结束了（草稿 VM 销毁），延迟一下再清栈。
     *
     * **不能靠 `ChatPie.onDestroy` 判生死**：QQ 复用 pie 实例，切换聊天时它也会触发，
     * 照它弹栈会把紧接着要用的那个会话提前弹掉（表现为「第二个聊天开始就没会话」）。
     * 草稿 VM 则是每页一个，销毁即这一页结束了。
     *
     * 延迟 [PAGE_CLOSE_DELAY_MS] 是为了跨过「切聊天」：新聊天页会在这个窗口内新建
     * 草稿 VM、把 generation 递上去，这次关闭就自动作废了。
     */
    fun schedulePageClose() {
        val g = pageGeneration.get()
        mainHandler.postDelayed({
            if (pageGeneration.get() != g) {
                Log.i(TAG, "page close: cancelled, another chat page opened")
                return@postDelayed
            }
            pageAlive = false
                val had = synchronized(lock) { stack.isNotEmpty() }
            if (!had) return@postDelayed
            synchronized(lock) {
                stack.clear()
                invalidateMemoLocked()
            }
            // **不能**把 newestOwner 一起清掉：它是「最近一个聊天页」的唯一句柄，
            // recoverFromScreen 全靠它。清掉之后栈空 + 无句柄，就再也认不回来。
            Log.i(TAG, "page close: chat page is gone, stack cleared")
            notifyIfChanged(forceResolve = true)
        }, PAGE_CLOSE_DELAY_MS)
    }

    /**
     * 栈空时把最近见过的那个聊天页重新认回来。
     *
     * 这是「第二次及以后打开聊天」的**唯一**通路：切换聊天时清栈把栈清空了，而 AIO
     * 创建事件未必还赶得上（实测第二个聊天经常一条 capture 都没有），所以除了草稿 VM
     * 构造那一刻（也就是 [notePageOpened] 之后）根本没有任何别的信号。
     *
     * 之所以敢认回来：contact 和 key 都是**现读**的，读到的就是「此刻」这个聊天页正在
     * 绑定的会话 —— 复用的 pie 已经换绑到新聊天了，读到的自动就是新会话的 key。残留也
     * 不用担心：认回来只会发生在草稿 VM 构造时（聊天页正在打开），而认回来时 pie 若还没
     * 绑上会话就读不到 key，气泡不会显示，延迟复查会继续跟。
     */
    fun recoverFromScreen() {
        val owner = newestOwner ?: return
        if (!sessionClosed) {
            // 栈还在：只有栈顶就是**同一个 pie** 时才重读，否则是真的开着上层
            // 聊天（气泡里再开一个聊天那种），不能动。
            val top = synchronized(lock) { stack.lastOrNull()?.owner }
            if (top !== owner) return
        }
        Log.i(TAG, "recover: re-read from ${owner.javaClass.name}")
        captureFrom(owner)
    }

    /** 当前会话类型：1=C2C，100=群，200=频道。取不到返回 null。 */
    fun currentChatType(): Int? = chatTypeOf(currentContact)

    /** 当前会话里对方（好友）的 uin。 */
    fun currentFriendUin(): String? = uinOf(currentContact, groups = false)

    /** 当前会话里群号。 */
    fun currentGroupUin(): String? = uinOf(currentContact, groups = true)

    /**
     * 这个会话对象是不是群聊。发送路径要判**实际发出去的那个对象**，不能拿当前会话顶替 ——
     * 用户可以在 A 群挂着窗口、切到 B 群去发消息。
     *
     * 带身份记忆化：发送路径每条消息都问一次，而 [chatTypeOf] 在 QQ 那些混淆类上通常要落到
     * 「扫全部 int 字段」那档兜底 —— 一串包着 runCatching 的反射读，逐条重跑太贵。会话对象的
     * chatType 不会变，记住上一次就行。
     */
    fun isGroupChat(obj: Any?): Boolean {
        if (obj == null) return false
        if (lastGroupRef?.get() === obj) return lastGroupValue
        val type = chatTypeOf(obj)
        // 只在真的读出类型时才记。和 [peerUidOfStable] 同一个道理：AIOContact 是先构造
        // 后填，类型还没填上时读出来是 null —— 那时候把 false 缓存住，就等于把「还没读
        // 到」永久冻成「不是群聊」，这个会话后面一律按单聊判。宁可多算一次。
        if (type == null) return false
        val v = type == CHAT_TYPE_GROUP || type == CHAT_TYPE_GUILD
        lastGroupRef = WeakReference(obj)
        lastGroupValue = v
        return v
    }

    @Volatile
    private var lastGroupRef: WeakReference<Any>? = null

    @Volatile
    private var lastGroupValue = false

    private fun invalidateMemoLocked() {
        memo = null
    }

    /**
     * AIO 是「先构造、后填」，所以捕获那一刻往往还读不到 key。这里在之后几个时间点
     * 再复查：一旦 key 出来了就重新广播，气泡和面板会自己跟上，不需要用户重进聊天。
     */
    private fun scheduleResolve(owner: Any) {
        // 捕获是高频事件（一个聊天页内 setter 能触发十几次），每个捕获都排三条消息的话
        // 主线程队列会被反射任务淹掉。同一个 owner 只保留一组待复查。
        synchronized(resolveLock) {
            if (resolvePendingOwner === owner) return
            resolvePendingOwner = owner
        }
        for ((i, delay) in RESOLVE_DELAYS.withIndex()) {
            mainHandler.postDelayed({
                // 清理必须走 finally：早退那条路（栈顶已经不是这个 owner 了）同样要把
                // 占位放掉，否则这个 pie 这次进程内就再也排不上复查了 —— 而 recover
                // 还会把同一个 pie 重新认回栈顶。
                try {
                    if (currentOwner !== owner) return@postDelayed
                    // forceResolve：期间栈顶的 source 可能已经换成更新的那个 AIO 对象了。
                    notifyIfChanged(forceResolve = true)
                    if (i == RESOLVE_DELAYS.lastIndex && resolveMemoized().second == null) {
                        dumpContactOnce()
                        dumpSourceOnce()
                    }
                } finally {
                    if (i == RESOLVE_DELAYS.lastIndex) {
                        synchronized(resolveLock) {
                            if (resolvePendingOwner === owner) resolvePendingOwner = null
                        }
                    }
                }
            }, delay)
        }
    }

    private val resolveLock = Any()

    @Volatile
    private var resolvePendingOwner: Any? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    //
    // 这个 hook 挂在 AIOParam / AIOSession 的构造器和**每一个单参 setter** 上，QQ 里这两个
    // 类被触发得有多频繁一直没人真的量过，全靠估。这里按 5 秒窗口统计一下：进来多少、被门
    // 挡掉多少、其中多少是同一个对象的重复 setter（早退直接吃掉）、真正强制重反射多少次、
    // 以及这些分别花了多少时间。看完真实数字再决定值不值得为它改结构。
    //
    // LongAdder：多线程累加不 contended，read-modify-write 也不会丢计数。
    private val statCaptures = java.util.concurrent.atomic.LongAdder()
    private val statGated = java.util.concurrent.atomic.LongAdder()
    private val statForced = java.util.concurrent.atomic.LongAdder()
    private val statCaptureNanos = java.util.concurrent.atomic.LongAdder()
    private val statResolveNanos = java.util.concurrent.atomic.LongAdder()

    private val statTask = object : Runnable {
        override fun run() {
            val captures = statCaptures.sumThenReset()
            if (captures > 0) {
                val gated = statGated.sumThenReset()
                val forced = statForced.sumThenReset()
                val capNs = statCaptureNanos.sumThenReset()
                val resNs = statResolveNanos.sumThenReset()
                Log.i(
                    TAG,
                    "stats/5s: capture=$captures gated=$gated forced=$forced " +
                        "captureUs=${capNs / 1000} resolveUs=${resNs / 1000} " +
                        "avgCaptureUs=${capNs / captures / 1000}",
                )
            } else {
                // 空闲窗口把其余的也清掉，免得下次打印时算进上一个窗口
                statGated.reset(); statForced.reset()
                statCaptureNanos.reset(); statResolveNanos.reset()
            }
            mainHandler.postDelayed(this, STATS_WINDOW_MS)
        }
    }

    private fun ensureStats() {
        if (statsStarted) return
        statsStarted = true
        mainHandler.postDelayed(statTask, STATS_WINDOW_MS)
    }

    @Volatile
    private var statsStarted = false

    private const val STATS_WINDOW_MS = 5000L

    /** AIO 对象上也没有可读的 uid 时，把它和它的 contact 一起打一次，方便对不上时定位。 */
    private fun dumpSourceOnce() {
        if (sourceDumped) return
        val src = currentSource ?: return
        val contact = currentContact
        if (contact == null) {
            sourceDumped = true
            Log.w(TAG, "no AIOContact under ${src.javaClass.name}. fields: ${describeFields(src)}")
            return
        }
        if (peerUidOf(contact) != null) return
        sourceDumped = true
        Log.w(
            TAG,
            "uid not readable: source=${src.javaClass.name} contact=${contact.javaClass.name}" +
                " fields: ${describeFields(contact)}"
        )
    }

    @Volatile
    private var sourceDumped = false

    private fun hex(o: Any) = Integer.toHexString(System.identityHashCode(o))

    /**
     * key 一直是 null 时，把 AIOContact 的字段布局打一次。字段名是混淆的、逐版本变动，
     * 有了这份输出就不用再猜了（只解释一次，避免刷屏）。
     */
    private fun dumpContactOnce() {
        if (dumped) return
        val contact = currentContact ?: return
        dumped = true
        Log.w(TAG, "AIOContact has no readable uid. fields: ${describeFields(contact)}")
    }

    private fun describeFields(obj: Any): String {
        val sb = StringBuilder()
        var c: Class<*>? = obj.javaClass
        var n = 0
        while (c != null && c != Any::class.java && n < 60) {
            for (f in c.declaredFields) {
                if (f.isSynthetic) continue
                if (n++ >= 60) break
                f.isAccessible = true
                val v = runCatching { f.get(obj) }.getOrNull()
                sb.append(c.simpleName).append('.').append(f.name)
                    .append(':').append(f.type.name).append('=')
                when (v) {
                    null -> sb.append("null")
                    is CharSequence -> sb.append('"').append(v).append('"')
                    else -> sb.append(v.javaClass.simpleName).append('@').append(v.hashCode())
                }
                sb.append(" | ")
            }
            c = c.superclass
        }
        return sb.toString()
    }

    /**
     * 「事件源」和「pie 自己挂着的 AIO」读出的会话不一样 —— 打一条日志。
     *
     * 这是当前设计里最薄的一环：[captureFrom] 让**最新那个** AIO 事件赢，而聊天页里并不只有
     * 聊天本身一个 AIO 在动（消息列表、推荐条、贴图面板都会各自 set 一次），所以理论上有一个
     * 跟当前会话无关的 AIO 抢走 [stack] 顶上的 source，就会显示成别人的会话。真发生了，日志这
     * 一行能直接指出是哪个对象抢的；没发生就当没这回事。
     */
    private fun reportSourceDisagreement(owner: Any, source: Any, chosenKey: String?) {
        val fromPie = peerUidOf(findAIOContact(owner))
        if (fromPie == null || fromPie == chosenKey) return
        if (disagreeSource === source && disagreeKey == chosenKey) return
        disagreeSource = source
        disagreeKey = chosenKey
        Log.w(
            TAG,
            "session source disagreement: event source=${source.javaClass.name}" +
                "-> $chosenKey, but the chat page itself holds $fromPie; " +
                "if the bubble shows the wrong contact, this is the culprit",
        )
    }

    @Volatile
    private var disagreeSource: Any? = null

    @Volatile
    private var disagreeKey: String? = null

    private fun resolveMemoized(force: Boolean = false): Pair<Any?, String?> {
        val now = SystemClock.uptimeMillis()
        // owner 和 source 必须一起取：分两次加锁的话，栈顶在这中间换掉就会拼出
        // 「A 的 pie + B 的 AIO 对象」这种根本不存在的组合。
        val entry = synchronized(lock) { stack.peek() }
        val owner = entry?.owner
        val source = entry?.source
        val m = memo
        if (!force && m != null && owner != null && owner === m.owner && source === m.source &&
            now - m.at < RESOLVE_MEMO_MS
        ) {
            return m.contact to m.key
        }
        var contact: Any? = null
        if (source != null) contact = findAIOContact(source)
        // source 上没读到才退回 pie（key 是现读的，所以读到的就是当下这个会话）
        if (contact == null && owner != null && owner !== source) {
            contact = findAIOContact(owner)
        }
        val key = peerUidOf(contact)
        if (force && contact != null && source != null && owner != null && owner !== source) {
            reportSourceDisagreement(owner, source, key)
        }
        memo = Memo(owner, source, contact, key, now)
        return contact to key
    }

    /**
     * 会话（key / 是否关闭）变了才广播给气泡。
     *
     * [forceResolve] 只是绕过短缓存重新读一遍（捕获时 pie 可能已经换成别的会话了），
     * **不等于**一定要广播：AIO 创建和 setter 一秒能触发十几次，无条件广播会把面板
     * 重建十几次、把诊断文件写十几次。
     */
    private fun notifyIfChanged(forceResolve: Boolean = false) {
        val (_, key) = resolveMemoized(forceResolve)
        val closed = sessionClosed
        if (key == notifiedKey && closed == notifiedClosed) return
        notifiedKey = key
        notifiedClosed = closed
        Log.i(TAG, "current session -> key=$key closed=$closed depth=$depth")
        KuchiguseCore.onTrackedSessionChanged(key, closed)
    }

    /** 只有 ChatPie 继承链上的对象配当「主人」。按类名往上找，不触发类加载。 */
    private fun isPie(obj: Any): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            if (c.name == CHAT_PIE) return true
            c = c.superclass
        }
        return false
    }

    /**
     * 从任意宿主对象里找出 `AIOContact`：先按类型找直接字段，找不到再顺着
     * `AIOParam -> AIOSession` 往下钻一层。QQ 的 AIO 数据类字段名是混淆的
     * （不同版本还换过字母），按类型找才不会跟着版本一起失效。
     */
    fun findAIOContact(root: Any?): Any? = findAIOContact(root, 0)

    private fun findAIOContact(root: Any?, depth: Int): Any? {
        if (root == null || depth > 3) return null
        val cls = root.javaClass
        if (cls.name == AIO_CONTACT) return root
        findFieldByTypeName(root, AIO_CONTACT)?.let { return it }
        for (name in AIO_HOLDERS) {
            findFieldByTypeName(root, name)?.let { nested ->
                findAIOContact(nested, depth + 1)?.let { return it }
            }
        }
        // 兜底：宿主自己的字段里可能直接放着别处的 AIOSession（比如草稿 VM 持有
        // 的是包装过的对象），只在 com.tencent.aio 包内递归，避免扫穿整个对象图。
        if (depth < 2) {
            for (f in extractorOf(cls).all) {
                if (f.type.isPrimitive || f.type.isArray) continue
                if (!f.type.name.startsWith("com.tencent.aio")) continue
                val v = runCatching { f.get(root) }.getOrNull() ?: continue
                findAIOContact(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun findFieldByTypeName(obj: Any, typeName: String): Any? {
        // allFieldsUpTo() 的结果已经在 Extractor 里缓存好了，别再为了一次查找重扫一遍继承链。
        for (f in extractorOf(obj.javaClass).all) {
            if (f.type.name != typeName) continue
            if (Modifier.isStatic(f.modifiers)) continue
            val v = runCatching { f.get(obj) }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    /**
     * 取一个会话对象（`AIOContact` 或发送侧的 `Contact`）的会话 key。
     *
     * 先从对象上反射取值口，按「QQ 号 → uid」挑：名单里写下来的是用户自己认得的号码，
     * 只有拿不到 QQ 号的会话（频道之类只有 uid 的）才退回去用 uid。取值口分四层：
     * 无参 String getter（名字像 uid/uin 的，公开 API 稳定，优先）、全部 String 字段
     * （AIOContact 的字段名是混淆的且逐版本变动，只有这一层不挑名字）、数字型 getter、
     * 数字型字段。
     *
     * 挑到 `u_` 开头的 uid 后统一交给 [finalizeKey] 去换 QQ 号。面板侧和发送侧都必须
     * 走同一个 [finalizeKey]，否则同一个人会得到两个不同的 key，名单就白配了。
     */
    fun peerUidOf(obj: Any?): String? {
        if (obj == null) return null
        if (obj is CharSequence) return normalizeUid(obj.toString())?.let { finalizeKey(it) }
        val ex = extractorOf(obj.javaClass)
        // 每个取值口只反射读一遍（getter 列表读两次就是白读一遍），然后分层挑。
        val fromGetters = readAll(ex.getters, obj)
        val fromFields = readAll(ex.fields, obj)
        val picked = pickQqNumber(fromGetters)
            ?: pickQqNumber(fromFields)
            ?: pickQqNumberFromNumbers(ex.numGetters, ex.numFields, obj)
            ?: pickUid(fromGetters)
            ?: pickUid(fromFields)
            ?: return null
        return finalizeKey(picked)
    }

    /**
     * 把「对象上读到的原始标识」收敛成最终 key。
     *
     * QQ 9.x 的会话对象上不存号码了（实测 [AIOContact] 只有 uid、类型、昵称），所以
     * 挑出来的多半是 `u_` 开头的 uid，得去问 QQ 自己的关系链接口换。已经是纯数字的
     * （老版本 QQ 直接给 uin）就原样用。
     *
     * 换不到就退回 uid：面板侧和发送侧读的是同一个 uid，退回去不会让判定错配，只是
     * 界面上显示成 `u_` 开头的串。
     */
    private fun finalizeKey(raw: String): String =
        if (raw.startsWith(UID_PREFIX)) UinLookup.uinFromUid(raw) ?: raw else raw

    /**
     * 从数字型取值口里挑 QQ 号。
     *
     * 顺序和 [pickQqNumber] 一样：getter 优先于字段。范围下限卡在 [MIN_QQ_NUMBER]：
     * 群号也是纯数字，而 `seq`、`chatType` 这类小整数也混在被筛出来的字段里，
     * 卡一个明显不可能的下限才能把它们挡掉。
     */
    private fun pickQqNumberFromNumbers(
        getters: List<Method>,
        fields: List<Field>,
        obj: Any,
    ): String? {
        for (s in getters + fields) {
            val v = runCatching {
                when (s) {
                    is Method -> (s.invoke(obj) as? Number)?.toLong()
                    is Field -> (s.get(obj) as? Number)?.toLong()
                    else -> null
                }
            }.getOrNull() ?: continue
            if (v >= MIN_QQ_NUMBER) return v.toString()
        }
        return null
    }

    /**
     * 发送侧专用：contact 是构造完就填好的，值不会变，所以可以按对象身份缓存。
     *
     * 不能直接缓存 [peerUidOf] 的结果 —— AIOContact 是「先构造后填」，同一个对象前后
     * 读到的 uid 不一样，缓存住就等于把「未填」永久冻结。缓存是这一个对象一份，
     * 换成别的 Contact 就自动失效。
     */
    fun peerUidOfStable(obj: Any?): String? {
        if (obj == null) return null
        if (lastStableRef?.get() === obj) return lastStableKey
        val key = peerUidOf(obj)
        lastStableRef = WeakReference(obj)
        lastStableKey = key
        return key
    }

    /** 弱引用：只是去重用的，不该因为它把一个 Contact 钉在内存里。 */
    @Volatile
    private var lastStableRef: WeakReference<Any>? = null

    @Volatile
    private var lastStableKey: String? = null

    /**
     * 读一批取值口。
     *
     * `isAccessible` 在 [extractorOf] 里已经设过一次，这里不再重复 —— 那是一次反射写，
     * 而这条路径每条消息、每次捕获都要走。
     */
    private fun <T> readAll(slots: List<T>, obj: Any): List<String> {
        val out = ArrayList<String>(slots.size)
        for (s in slots) {
            val v = runCatching {
                @Suppress("UNCHECKED_CAST")
                when (s) {
                    is Method -> s.invoke(obj) as? String
                    is Field -> s.get(obj) as? String
                    else -> null
                }
            }.getOrNull()
            if (v != null) out.add(v)
        }
        return out
    }

    private fun <T> readInts(slots: List<T>, obj: Any): Int? {
        for (s in slots) {
            val v = runCatching {
                @Suppress("UNCHECKED_CAST")
                when (s) {
                    is Method -> s.invoke(obj) as? Int
                    is Field -> s.get(obj) as? Int
                    else -> null
                }
            }.getOrNull()
            if (v != null && v in CHAT_TYPES) return v
        }
        return null
    }

    // computeIfAbsent 而不是 getOrPut：后者在并发下会重复构建整个取值器表。
    private fun extractorOf(cls: Class<*>): Extractor = extractorCache.computeIfAbsent(cls) {
        val all = runCatching { cls.allFieldsUpTo() }.getOrDefault(emptyList())
        val methods = runCatching { cls.methods.toList() }.getOrDefault(emptyList())
        // 必须放开访问权限：AIOContact 的字段都是 private，声明类本身也是包内可见，
        // 不设 isAccessible 的话 Field.get / Method.invoke 直接抛 IllegalAccessException，
        // 而 runCatching 会把它吞成 null —— 表现出来就是「字段明明有值却读成空」。
        //
        // **只在这里设一次**。setAccessible 不是免费的：每次调用都要重新做一遍访问检查。
        // 之前每读一个字段就设一次，实测单次捕获要几十微秒，其中大半花在这上面；缓存里
        // 存的就是这些 Field 对象，accessible 状态跟着对象一起留着，读的时候直接 get。
        for (f in all) runCatching { f.isAccessible = true }
        for (m in methods) runCatching { m.isAccessible = true }
        Extractor(
            all = all,
            getters = methods.filter { m ->
                m.parameterTypes.isEmpty() &&
                    m.returnType == String::class.java &&
                    KEY_GETTER_HINTS.any { hint -> m.name.contains(hint, ignoreCase = true) }
            },
            fields = all.filter { it.type == String::class.java },
            typeGetters = methods.filter { m ->
                m.parameterTypes.isEmpty() && m.returnType == Int::class.javaPrimitiveType &&
                    (m.name.contains("chatType", ignoreCase = true) || m.name.equals("getType", ignoreCase = true))
            },
            typeFields = all.filter { f ->
                f.type == Int::class.javaPrimitiveType &&
                    (f.name.contains("chatType", ignoreCase = true) || f.name.equals("type", ignoreCase = true))
            },
            intFields = all.filter { it.type == Int::class.javaPrimitiveType },
            numGetters = methods.filter { m ->
                m.parameterTypes.isEmpty() && isNumericType(m.returnType) &&
                    NUMBER_HINTS.any { hint -> m.name.contains(hint, ignoreCase = true) }
            },
            numFields = all.filter { f ->
                isNumericType(f.type) &&
                    NUMBER_HINTS.any { hint -> f.name.contains(hint, ignoreCase = true) }
            },
        )
    }

    private fun isNumericType(t: Class<*>): Boolean =
        t == Long::class.javaPrimitiveType || t == Int::class.javaPrimitiveType ||
            t == java.lang.Long::class.java || t == Integer::class.java

    /**
     * 挑出 QQ 号 / 群号：必须是**纯数字**。
     *
     * 卡纯数字是必须的：扫字段时读到的不只有账号字段，还有昵称、备注、个性签名这些
     * 非数字的 String，不卡的话第一个被读到的昵称就成会话标识了，名单会跟着一起废掉。
     * [normalizeUid] 已经把 "0" 和空串挡掉了。
     */
    private fun pickQqNumber(values: List<String>): String? {
        for (raw in values) {
            val v = normalizeUid(raw) ?: continue
            if (v.all { it.isDigit() }) return v
        }
        return null
    }

    /** 挑出 uid：`u_` 开头且后面确实有东西。 */
    private fun pickUid(values: List<String>): String? {
        for (raw in values) {
            val v = normalizeUid(raw) ?: continue
            if (v.startsWith(UID_PREFIX) && v.length > UID_PREFIX.length) return v
        }
        return null
    }

    private fun normalizeUid(raw: String?): String? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty() || v == "0") return null
        return v
    }

    /**
     * 会话类型。优先找公开的 `getChatType()`；拿不到再扫名字像 chatType/type 的 int
     * 字段；名字也认不出来（实测就是混淆成 `d`）就扫全部 int 字段，取落在
     * {1, 100, 200} 里的那个 —— 这三个是 QQ 的 C2C / 群 / 频道。
     */
    private fun chatTypeOf(obj: Any?): Int? {
        if (obj == null) return null
        val ex = extractorOf(obj.javaClass)
        return readInts(ex.typeGetters, obj)
            ?: readInts(ex.typeFields, obj)
            ?: readInts(ex.intFields, obj)
    }

    /**
     * uin 形态的对方标识：[groups] 决定要群的还是要好友的。群号本身就是 uin 形态，
     * 老版本 QQ 直接就能拿到；9.x 只有 uid，交给 [peerUidOf] 走关系链接口换。
     */
    private fun uinOf(obj: Any?, groups: Boolean): String? {
        val type = chatTypeOf(obj)
        val isGroup = type == CHAT_TYPE_GROUP || type == CHAT_TYPE_GUILD
        if (groups != isGroup) return null
        return peerUidOf(obj)
    }

    private fun Class<*>.allFieldsUpTo(): List<Field> {
        val out = ArrayList<Field>()
        var c: Class<*>? = this
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.isSynthetic) continue
                out.add(f)
            }
            c = c.superclass
        }
        return out
    }

    private val AIO_HOLDERS = arrayOf(AIO_PARAM, AIO_SESSION)

    private const val UID_PREFIX = "u_"

    private const val CHAT_TYPE_C2C = 1
    private const val CHAT_TYPE_GROUP = 100
    private const val CHAT_TYPE_GUILD = 200

    private val CHAT_TYPES = setOf(CHAT_TYPE_C2C, CHAT_TYPE_GROUP, CHAT_TYPE_GUILD)
    private val KEY_GETTER_HINTS = arrayOf("peeruid", "uid", "uin")

    /** 数字型取值口里，哪些名字像 QQ 号。 */
    private val NUMBER_HINTS = arrayOf("uin", "qqnum", "qqnumber", "number")

    /**
     * 数字型 QQ 号的合理下限。
     *
     * 早期 QQ 号只有五到六位，但群号不会那么小；卡这个值是为了把 `chatType`（1/100/200）、
     * 消息 seq、时间戳这类小整数挡在外面 —— 它们同样在被筛出来的 int/long 字段里。
     */
    private const val MIN_QQ_NUMBER = 100_000L
}
