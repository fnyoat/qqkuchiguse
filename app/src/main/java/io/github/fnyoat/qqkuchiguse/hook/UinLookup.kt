package io.github.fnyoat.qqkuchiguse.hook

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 用 QQ 的关系链接口把 `u_` 开头的 uid 换成明文 QQ 号。
 *
 * QQ 9.x 的两个会话对象上都不存号码（实测 `AIOContact{d,e,h}` 和 `Contact{chatType,
 * peerUid}`），光扫字段拿不到；而 QQ 自己得知道这层对应关系，好友列表才显示得出来 ——
 * 那套关系在 `com.tencent.relation.common.api.IRelationNTUinAndUidApi` 上，经 QRoute 取。
 *
 * 拿不到就返回 null，调用方退回用 uid：uid 在两边一致，不会让判定错乱，只是界面上显示成
 * `u_` 开头的串。
 */
object UinLookup {
    private const val TAG = "KuchiguseUin"
    private const val UID_PREFIX = "u_"

    private const val IFACE = "com.tencent.relation.common.api.IRelationNTUinAndUidApi"
    private const val QROUTE = "com.tencent.mobileqq.qroute.QRoute"

    /** uid -> QQ 号。uid 到号码是一一对应的，缓存住就不用反复问 QQ。 */
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * 每学到一条新的映射就加一。
     *
     * 给「拿老名单条目去换号码」的迁移用：接口是慢慢就绪的，早先换不出来的条目，
     * 等接口能用了还得再试一次，所以下游得能看出「又学到新东西了，该重算」。
     */
    val generation = AtomicLong()

    /** 查失败的 uid 也要记住，不然每条消息都要重新反射找一遍接口。 */
    private val misses = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var api: Any? = null

    /**
     * 「已经确认过拿不到」了。
     *
     * 只在**真的问过一次**之后才置位。宿主 Application 还没接上时不算数 —— 那只是
     * 「现在还问不到」，不是「这东西不存在」。早置位会让整个 QQ 进程都换不出号码。
     */
    @Volatile
    private var apiUnavailable = false

    /**
     * 把 uid 换成 QQ 号；[uid] 不是 `u_` 开头、查不到、或结果不是纯数字，
     * 一律返回 null，由调用方决定用不用 uid 本身。
     */
    fun uinFromUid(uid: String): String? {
        if (!uid.startsWith(UID_PREFIX) || uid.length <= UID_PREFIX.length) return null
        cache[uid]?.let { return it }
        if (misses.containsKey(uid)) return null
        val inst = runCatching { apiInstance() }.getOrNull() ?: return null
        val out = runCatching { invoke(inst, uid) }.getOrNull()?.takeIf { isQqNumber(it) }
        if (out == null) {
            // 只在真的问过接口之后才记 miss：接口还没就绪导致的空结果不能记，
            // 否则那个 uid 这一辈子都不会再问了。
            misses[uid] = true
            return null
        }
        cache[uid] = out
        generation.incrementAndGet()
        return out
    }

    /**
     * 只认纯数字。
     *
     * 这层校验不是多余的：接口要是返回了 "0"、错误文案之类的东西，那个值会被缓存下来
     * 当成会话 key 到处用，名单里就会冒出一堆莫名其妙、还删不掉的条目。
     */
    private fun isQqNumber(v: String): Boolean =
        v.isNotEmpty() && v.all { it.isDigit() } && v != "0"

    private fun invoke(inst: Any, uid: String): String? {
        val m = inst.javaClass.methods.firstOrNull {
            it.name == "getUinFromUid" && it.parameterTypes.size == 1
        } ?: return null
        return m.invoke(inst, uid) as? String
    }

    /**
     * 走 QRoute 拿关系链接口的实现。
     *
     * 接口和 QRoute 都是 QQ 的类，只能反射：模块编译时看不到它们。取宿主
     * ClassLoader 用 Application 自己的，它就是 QQ 的 ClassLoader。
     */
    private fun apiInstance(): Any? {
        api?.let { return it }
        if (apiUnavailable) return null
        synchronized(this) {
            api?.let { return it }
            if (apiUnavailable) return null
            val app = runCatching { KuchiguseCore.app() }.getOrNull()
            if (app == null) {
                // 不置 apiUnavailable：Application 还没接上，下次再问。
                return null
            }
            // 到这一步就真的问过了，是成是败都是定论，可以把「别再试了」记上了。
            apiUnavailable = true
            val loader = app.javaClass.classLoader
            if (loader == null) return null
            api = try {
                val iface = Class.forName(IFACE, false, loader)
                val qroute = Class.forName(QROUTE, false, loader)
                // QRoute.api 有重载，按「一个 Class 参数」挑，不写死签名。
                val apiMethod = qroute.methods.firstOrNull {
                    it.name == "api" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Class::class.java
                }
                if (apiMethod == null) {
                    Log.w(TAG, "QRoute.api(Class) not found")
                    return null
                }
                apiMethod.invoke(null, iface)
            } catch (t: Throwable) {
                Log.w(TAG, "resolve $IFACE failed: ${t.message}")
                null
            }
        }
        return api
    }
}
