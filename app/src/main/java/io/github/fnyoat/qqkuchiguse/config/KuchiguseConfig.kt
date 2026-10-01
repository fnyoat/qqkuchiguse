/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class AffixKind { PREFIX, SUFFIX }

enum class ListMode { WHITELIST, BLACKLIST }

data class AffixItem(
    val kind: AffixKind,
    val text: String,
    val weight: Int = 100
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", if (kind == AffixKind.PREFIX) "prefix" else "suffix")
        .put("text", text)
        .put("weight", weight)

    companion object {
        fun fromJson(o: JSONObject): AffixItem {
            val kind = if (o.optString("kind", "suffix") == "prefix") AffixKind.PREFIX else AffixKind.SUFFIX
            return AffixItem(kind, o.optString("text", ""), o.optInt("weight", 100))
        }
    }
}

data class Replacement(
    val from: String,
    val to: String
) {
    fun toJson(): JSONObject = JSONObject().put("from", from).put("to", to)

    companion object {
        fun fromJson(o: JSONObject): Replacement =
            Replacement(o.optString("from", ""), o.optString("to", ""))
    }
}

enum class Scope { PER_SENTENCE, WHOLE_MESSAGE }

/**
 * 盘古之白：汉字和西文相接时补一个空格，管多大范围。
 *
 * - [OFF] 不补。
 * - [AFFIX] 只在贴口癖的那一个接缝上补，就是原来那个 `affixSpaceCjkLatin` 开关。
 * - [WHOLE_MESSAGE] 整条消息扫一遍，「这是QQ消息」变「这是 QQ 消息」，正文内部也补。
 *
 * 英文接英文不算盘古（「hi im cat」本来就有词间空格），那是 [KuchiguseConfig.affixSpaceLatinLatin]
 * 单独管的「别把 meow 粘成 catmeow」，所以不跟着这个模式走。
 */
enum class PanguMode { OFF, AFFIX, WHOLE_MESSAGE }

/**
 * 图片、表情、平底锅那三种元素的外显描述总闸，只区分「碰不碰」。
 *
 * - [OFF] 不写，气泡里只有图，保持 QQ 原样。
 * - [DEDICATED] 旧档位，等价于现在的 [CaptionMode.AFFIX]：用公共表
 *   [KuchiguseConfig.imageDescAffixes]。只为了能读老配置才留着。
 * - [MAIN] 开。具体每一类怎么处理由 [KuchiguseConfig.imageDescKinds] 里那一类的
 *   [CaptionMode] 决定，这一档在分类型配置之后只是总闸。
 *
 * 底句不再写死：每一类在 [CaptionKindConfig.bases] 里配随机底句表，口癖从主表
 * [KuchiguseConfig.affixes] 还是公共表 [KuchiguseConfig.imageDescAffixes] 抽由那一类的
 * [CaptionMode] 决定。
 */
enum class ImageDescMode { OFF, DEDICATED, MAIN }

/**
 * 完整插件配置。
 *
 * 默认：后缀"喵"，替换"怎么"->"怎喵"，分句处理，忽略 url/emoji/随机串。
 * 概率由三道门控组合：
 *  1. 总概率池
 *  2. 句长概率：达到 [maxLength] 必定处理，更短按梯度缩放
 *  3. 对仗强制：[antithesisEnabled] 时工整对仗句必定加后缀
 */
data class KuchiguseConfig(
    val enabled: Boolean = true,
    val probabilityExpr: String = "1",
    val scope: Scope = Scope.PER_SENTENCE,
    val affixes: List<AffixItem> = listOf(AffixItem(AffixKind.SUFFIX, "喵", 100)),
    /**
     * 连续抑制：上次选中的 affix 下次权重按百分比下调，用来打散连着一样的口癖。
     * 0 = 关闭（即纯加权随机）。取值 0~100，1 个 affix 时无效果。
     */
    val affixRepeatPenalty: Double = 20.0,
    /**
     * 是否允许前缀与后缀同时出现在一条消息里。
     *
     * 关闭（默认）时保留老约束：一段消息里只出现一种口癖类型，第一句抽到前缀就全用前缀。
     * 打开后每句独立抽，一段消息里可以既有前缀也有后缀。只有逐句模式才有区别。
     */
    val allowPrefixSuffixTogether: Boolean = false,
    /**
     * 汉字↔西文之间补空格的适用范围，见 [PanguMode]。默认关：盘古会把「3.14」「C++」
     * 这类刻意不带空格的写法也改掉，要不要开由用户自己定。
     */
    val panguMode: PanguMode = PanguMode.OFF,
    /**
     * 英文接英文时在口癖接缝补一个空格，避免口癖直接粘在最后一个词上（「catmeow」）。
     *
     * 不属于盘古，跟 [panguMode] 互不影响：西文之间本来就有词间空格，只有贴口癖时
     * 两边都可能是紧挨着的字母，才需要单独补。
     */
    val affixSpaceLatinLatin: Boolean = true,
    /** 图片/表情外显描述的口癖来源，见 [ImageDescMode]。默认关。 */
    val imageDescMode: ImageDescMode = ImageDescMode.OFF,
    /**
     * 公共口癖表：四类图片/表情里凡是选了 [CaptionMode.AFFIX] 的，都从这一张里抽。
     *
     * 单独一份是因为这两种消息的观感和正文不一样：正文里要克制的口癖，挂在图片气泡上往往
     * 正好（气泡小、字短，多两个口癖不挤）。留空等于这一档跟主表一样。
     *
     * 默认给一条 `meow` 是让这一档装上就有东西可抽；四类的默认档是 [CaptionMode.MAIN_AFFIX]，
     * 没选这一档时这张表不参与，所以这条默认值不会自己贴到消息上。
     */
    val imageDescAffixes: List<AffixItem> = listOf(AffixItem(AffixKind.SUFFIX, "meow", 100)),
    /**
     * 每类图片/表情自己的配置，键是 [CaptionKind]。
     *
     * 某一类没在表里配过，就拿 [CaptionKindConfig] 的默认值，所以旧配置不用重填。
     * 取值一律走 [captionKind] / [affixesFor] / [basesFor]，别直接读这张表。
     *
     * 旧版那两个全局底句字段（`imageDescRandomBase` / `imageDescBases`）只在读配置时
     * 用来铺迁移，已经照搬进每一类，不再作为运行时的回落。
     */
    val imageDescKinds: Map<CaptionKind, CaptionKindConfig> = emptyMap(),
    /**
     * 假装机器人（中文）：消息末尾没有标点时补一个中文句号「。」。
     *
     * 只是一条文风开关，和口癖抽签完全无关——抽签没中、或者根本没配口癖，这一条照样
     * 生效。已经带标点（含英文 . ! ? 和逗号分号）就不动，末尾是链接或密钥也不动，
     * 免得把 URL 改坏。
     */
    val botChinesePeriod: Boolean = false,
    /**
     * 「下一行算句尾」：多行消息里每个换行都算一句的结束，于是每行末尾都补中文句号。
     *
     * 只管句尾补号这一件事，别的切分照旧：行内的逗号、括号、句号仍然切分，每一半仍然
     * 各自抽一次口癖。
     */
    val lineEndsSentence: Boolean = false,
    /**
     * 句尾波浪号：只给**最后一句**的口癖后面补一个「~」。
     *
     * 「你好，今天真好」→ 「你好，今天真好喵~」。前面几句不受影响，末尾那句没抽中口癖
     * （概率没中、被屏蔽词或链接拦下）也不补 —— 这个 ~ 是跟着口癖的，不是句尾装饰。
     *
     * 和 [botChinesePeriod] 互斥：句号开关一开就完全不加。那句号会补在口癖**之后**
     * （「今天真好喵。」），再插一个 ~ 就成了「今天真好喵~。」，中英混排看着别扭；
     * 而整条收尾只能有一个符号，与其猜用户想要哪个，不如让用户自己二选一。
     */
    val affixTailTilde: Boolean = false,
    val replacements: List<Replacement> = listOf(Replacement("怎么", "怎喵")),
    val ignoreUrl: Boolean = true,
    val ignoreEmoji: Boolean = true,
    val ignoreRandomLike: Boolean = true,
    /**
     * 悬浮面板标题里不显示会话号码（好友 QQ 号和群号都算）。
     *
     * 只影响显示，不影响判定：[FloatController] 里 key 照常算、照常开关会话，
     * 只是标题不把它拼上去。所以名单里写的还是号码，两边判定不会错配。
     */
    val hideSessionNumber: Boolean = false,
    /** 长度门槛：达到该字符数的句子必定处理；更短按 [lengthGradient] 缩放概率。0 = 关闭。 */
    val maxLength: Int = 5,
    /** 长度梯度：>1 更陡（短句概率更低），<1 更平缓。 */
    val lengthGradient: Double = 1.0,
    /** 稳定性：预期操作数的 40% + [stabilityMinGuaranteed] 为必定命中，其余按各自概率。0 关闭。 */
    val stability: Double = 0.4,
    /** 最小保底数：至少必定命中的位置数（保底）。默认 3。 */
    val stabilityMinGuaranteed: Int = 3,
    /** 密集时自动降低总概率池：可操作位 ≥ 5 且 (pool × operable) / 总字符 ≥ 0.4 时，pool 自动降至 80%。 */
    val lengthAutoAdjustPool: Boolean = false,
    /** 对仗强制：工整对仗句必定加后缀，忽略上述门控。 */
    val antithesisEnabled: Boolean = false,
    val blockKeywords: List<String> = emptyList(),
    /** 悬浮球开关。 */
    val floatEnabled: Boolean = true,
    /** 悬浮球大小。 */
    val floatSizeDp: Int = 20,
    /** 悬浮球不透明度。 */
    val floatAlpha: Float = 0.5f,
    /**
     * 单聊这一类当前**生效**的是哪一份名单。另一份完全不参与判定。
     *
     * 默认黑名单：名单空着就是「谁都不拦」，装完先按老样子全开，不会一装上就什么都不
     * 生效；要收紧再自己切到白名单。
     *
     * - [ListMode.WHITELIST]：看 [singleWhitelist]
     * - [ListMode.BLACKLIST]：看 [singleBlacklist]
     */
    val singleListMode: ListMode = ListMode.BLACKLIST,
    /**
     * 群聊这一类当前**生效**的是哪一份名单。另一份完全不参与判定。默认同 [singleListMode]。
     *
     * - [ListMode.WHITELIST]：看 [groupWhitelist]
     * - [ListMode.BLACKLIST]：看 [groupBlacklist]
     */
    val groupListMode: ListMode = ListMode.BLACKLIST,
    /**
     * 单聊白名单：只有这几个单聊生效，其余单聊一律不生效。
     *
     * 配合 [singleListMode] 使用：名单内的听名单，名单外的一律不生效。
     */
    val singleWhitelist: Set<String> = emptySet(),
    /** 单聊黑名单：这几个单聊不生效，其余单聊一律生效。 */
    val singleBlacklist: Set<String> = emptySet(),
    /** 群聊白名单：只有这几个群聊生效，其余群聊一律不生效。 */
    val groupWhitelist: Set<String> = emptySet(),
    /** 群聊黑名单：这几个群聊不生效，其余群聊一律生效。 */
    val groupBlacklist: Set<String> = emptySet(),
) {

    /** 这一类当前生效的那份名单。 */
    fun activeList(isGroup: Boolean): Set<String> = listIn(isGroup, listModeFor(isGroup))

    /**
     * 这一类图片/表情自己的配置。没在 [imageDescKinds] 里配过的类型拿默认那份；
     * 口癖表留空由 [affixesFor] 兜，底句表留空就是不出随机底句。
     */
    fun captionKind(kind: CaptionKind): CaptionKindConfig =
        imageDescKinds[kind] ?: CaptionKindConfig()

    /**
     * 这一类要不要动。三个开关都得开：模块总开关、[imageDescMode]、这一类自己那个。
     *
     * [imageDescMode] 在分类型配置之后只剩下「总闸」这一个作用：它决定要不要碰图片/表情，
     * 具体每一类怎么处理由 [imageDescKinds] 里那一类的 [CaptionMode] 决定。
     */
    fun captionEnabled(kind: CaptionKind): Boolean =
        enabled && imageDescMode != ImageDescMode.OFF && captionKind(kind).mode != CaptionMode.OFF

    /**
     * 这一类抽签用的口癖表。只有 [CaptionMode.AFFIX] 才用自己那张表，其余档位一律走主表 ——
     * [CaptionMode.MAIN_AFFIX] 本来就是「跟主表一样」，写成自己那张优先会让这两个档位没区别。
     */
    fun affixesFor(kind: CaptionKind): List<AffixItem> =
        if (captionKind(kind).mode == CaptionMode.AFFIX) imageDescAffixes.ifEmpty { affixes } else affixes

    /** 只有 [CaptionMode.RANDOM_WHOLE_MSG] 才抽随机底句。 */
    fun captionRandomBase(kind: CaptionKind): Boolean =
        captionEnabled(kind) && captionKind(kind).mode == CaptionMode.RANDOM_WHOLE_MSG

    /** 这一类抽签用的底句表。留空就是这一类不出随机底句，底句仍旧用 QQ 那句。 */
    fun basesFor(kind: CaptionKind): List<String> = captionKind(kind).bases

    /** 这一类没在生效的那份里时，另一份（不参与判定的）名单，方便 UI 提示。 */
    fun inactiveList(isGroup: Boolean): Set<String> =
        listIn(isGroup, if (listModeFor(isGroup) == ListMode.WHITELIST) ListMode.BLACKLIST else ListMode.WHITELIST)

    /** 这一类当前生效哪份名单。 */
    fun listModeFor(isGroup: Boolean): ListMode = if (isGroup) groupListMode else singleListMode

    /** 这一类、指定那一份名单。 */
    fun listIn(isGroup: Boolean, mode: ListMode): Set<String> = when {
        isGroup && mode == ListMode.WHITELIST -> groupWhitelist
        isGroup -> groupBlacklist
        mode == ListMode.WHITELIST -> singleWhitelist
        else -> singleBlacklist
    }

    /**
     * 本会话是否真的会加口癖。
     *
     * 优先级：总开关 > 会话所属那一类的名单。两类各看各的，互不干涉。
     *
     * 名单在生效中：白名单里的一律生效、名单外的一律不生效；黑名单里的一律不生效、
     * 名单外的一律生效 —— 所以白名单在任何配置下都有意义，不会形同虚设。
     *
     * [sessionKey] 为 null 表示「还没算出 key」，此时名单里当然不会有它：白名单下按
     * 不生效处理，黑名单下按生效处理。
     */
    fun enabledForSession(sessionKey: String?, isGroup: Boolean): Boolean {
        if (!enabled) return false
        if (listModeFor(isGroup) == ListMode.BLACKLIST) {
            return !(!sessionKey.isNullOrBlank() && sessionKey in activeList(isGroup))
        }
        return !sessionKey.isNullOrBlank() && sessionKey in activeList(isGroup)
    }

    /**
     * 这个会话在不在**这一类**生效的那份名单里。面板开关显示的就是这个。
     *
     * 开关只管「在不在名单里」，不管「生效不生效」——后者由名单类型决定。
     */
    fun sessionListed(sessionKey: String?, isGroup: Boolean): Boolean =
        !sessionKey.isNullOrBlank() && sessionKey in activeList(isGroup)

    /**
     * 指定那一份名单里的会话键，排序后给 UI 用。
     * 排序是为了让 UI 稳定：写入顺序取决于用户先在哪个会话上点开关。
     */
    fun sessionsIn(isGroup: Boolean, mode: ListMode): List<String> = listIn(isGroup, mode).sorted()

    /** 这一类生效中的那份名单里的会话键。 */
    fun sessionsInList(isGroup: Boolean): List<String> = sessionsIn(isGroup, listModeFor(isGroup))

    /**
     * 拨动面板开关：只动这一类里**当前生效的那一份**，另一份一个字都不碰。
     *
     * 两份名单各自独立。开关只表达「生效的那份里有没有这个会话」，无权改另一份 ——
     * 曾经 `listed=true` 带着 `-key` 去删另一份，于是「白名单模式下永远放行 A」在切到
     * 黑名单模式屏蔽 A 时被静默删掉，切回来就没了，界面上还毫无提示。
     *
     * 一个会话同时在两份里不是脏数据，它就是「白名单放行 + 黑名单屏蔽」，判定只读生效
     * 那份，行为明确。删另一份只能显式做：名单页删除（[withoutListedIn]）或清空
     * （[withClearedList]）。空 key 直接忽略，写进去只会留下一条谁也匹配不上的幽灵条目。
     */
    fun withSessionListed(sessionKey: String, isGroup: Boolean, listed: Boolean): KuchiguseConfig =
        if (sessionKey.isBlank()) this
        else if (listed) addToActiveList(sessionKey, isGroup)
        else removeFromActiveList(sessionKey, isGroup)

    /** 加进当前生效的那一份，不动另一份。 */
    private fun addToActiveList(sessionKey: String, isGroup: Boolean): KuchiguseConfig {
        val whitelist = listModeFor(isGroup) == ListMode.WHITELIST
        return if (isGroup) {
            if (whitelist) copy(groupWhitelist = groupWhitelist + sessionKey)
            else copy(groupBlacklist = groupBlacklist + sessionKey)
        } else {
            if (whitelist) copy(singleWhitelist = singleWhitelist + sessionKey)
            else copy(singleBlacklist = singleBlacklist + sessionKey)
        }
    }

    /** 只从当前生效的那一份里移出，不动另一份。 */
    private fun removeFromActiveList(sessionKey: String, isGroup: Boolean): KuchiguseConfig {
        val whitelist = listModeFor(isGroup) == ListMode.WHITELIST
        return if (isGroup) {
            if (whitelist) copy(groupWhitelist = groupWhitelist - sessionKey)
            else copy(groupBlacklist = groupBlacklist - sessionKey)
        } else {
            if (whitelist) copy(singleWhitelist = singleWhitelist - sessionKey)
            else copy(singleBlacklist = singleBlacklist - sessionKey)
        }
    }

    /** 从指定的那一份名单里移除。 */
    fun withoutListedIn(isGroup: Boolean, mode: ListMode, sessionKey: String): KuchiguseConfig =
        when {
            isGroup && mode == ListMode.WHITELIST -> copy(groupWhitelist = groupWhitelist - sessionKey)
            isGroup -> copy(groupBlacklist = groupBlacklist - sessionKey)
            mode == ListMode.WHITELIST -> copy(singleWhitelist = singleWhitelist - sessionKey)
            else -> copy(singleBlacklist = singleBlacklist - sessionKey)
        }

    /** 清空指定的那一份名单。 */
    fun withClearedList(isGroup: Boolean, mode: ListMode): KuchiguseConfig =
        when {
            isGroup && mode == ListMode.WHITELIST -> copy(groupWhitelist = emptySet())
            isGroup -> copy(groupBlacklist = emptySet())
            mode == ListMode.WHITELIST -> copy(singleWhitelist = emptySet())
            else -> copy(singleBlacklist = emptySet())
        }

    /** 四份名单里有没有一条，判定前用来决定要不要去解析会话 key。 */
    fun hasAnyList(): Boolean =
        singleWhitelist.isNotEmpty() || singleBlacklist.isNotEmpty() ||
            groupWhitelist.isNotEmpty() || groupBlacklist.isNotEmpty()

    /** 长度门槛概率：达到 maxLength 为 1.0，更短按 gradient 缩放。 */
    fun lengthProbability(charCount: Int): Double {
        if (maxLength <= 0) return 1.0
        val c = charCount.coerceAtMost(maxLength)
        val raw = c.toDouble() / maxLength
        val g = if (lengthGradient.isFinite() && lengthGradient > 0) lengthGradient else 1.0
        return Math.pow(raw, g)
    }

    fun toJson(): String {
        val affixesArr = JSONArray().apply { affixes.forEach { put(it.toJson()) } }
        val replacementsArr = JSONArray().apply { replacements.forEach { put(it.toJson()) } }
        val blocksArr = JSONArray().apply { blockKeywords.forEach { put(it) } }
        fun sessionsArr(keys: Set<String>): JSONArray =
            JSONArray().apply { keys.sorted().forEach { put(it) } }
        return JSONObject()
            .put("enabled", enabled)
            .put("probability", probabilityExpr)
            .put("scope", if (scope == Scope.PER_SENTENCE) "sentence" else "whole")
            .put("affixes", affixesArr)
            .put("affixRepeatPenalty", affixRepeatPenalty)
            .put("allowPrefixSuffixTogether", allowPrefixSuffixTogether)
            .put("panguMode", panguMode.name.lowercase())
            .put("affixSpaceCjkLatin", panguMode != PanguMode.OFF)
            .put("affixSpaceLatinLatin", affixSpaceLatinLatin)
            .put("imageDescMode", imageDescMode.name.lowercase())
            .put("imageDescAffixes", JSONArray().apply { imageDescAffixes.forEach { put(it.toJson()) } })
            .put(
                "imageDescKinds",
                JSONObject().apply {
                    imageDescKinds.forEach { (kind, c) ->
                        put(
                            kind.name.lowercase(),
                            JSONObject()
                                .put("mode", c.mode.name)
                                .put("bases", JSONArray().apply { c.bases.forEach { put(it) } }),
                        )
                    }
                },
            )
            .put("botChinesePeriod", botChinesePeriod)
            .put("lineEndsSentence", lineEndsSentence)
            .put("affixTailTilde", affixTailTilde)
            .put("replacements", replacementsArr)
            .put("ignoreUrl", ignoreUrl)
            .put("ignoreEmoji", ignoreEmoji)
            .put("ignoreRandom", ignoreRandomLike)
            .put("hideSessionNumber", hideSessionNumber)
            .put("maxLength", maxLength)
            .put("lengthGradient", lengthGradient)
            .put("stability", stability)
            .put("stabilityMinGuaranteed", stabilityMinGuaranteed)
            .put("lengthAutoAdjustPool", lengthAutoAdjustPool)
            .put("antithesis", antithesisEnabled)
            .put("blocks", blocksArr)
            .put("floatEnabled", floatEnabled)
            .put("floatSizeDp", floatSizeDp)
            .put("floatAlpha", floatAlpha)
            .put("singleListMode", if (singleListMode == ListMode.WHITELIST) "whitelist" else "blacklist")
            .put("groupListMode", if (groupListMode == ListMode.WHITELIST) "whitelist" else "blacklist")
            .put("singleWhitelist", sessionsArr(singleWhitelist))
            .put("singleBlacklist", sessionsArr(singleBlacklist))
            .put("groupWhitelist", sessionsArr(groupWhitelist))
            .put("groupBlacklist", sessionsArr(groupBlacklist))
            .toString()
    }

    companion object {
        /**
         * 读分类型配置。表里没有这一类时，用旧的那几个全局字段给它铺一份。
         *
         * 迁移是**照搬**不是猜：旧版只有「关 / 单独设置这份 / 跟主设置一样」三档和一张共用表，
         * 所以每一类都拿到同一份 —— 这正是旧配置原本的行为，用户不用重填。只有在
         * `imageDescKinds` 整个对象都缺的时候才铺，读到部分就只补缺的那些。
         */
        private fun readCaptionKinds(
            obj: JSONObject?,
            mode: ImageDescMode,
            legacyRandomBase: Boolean,
            legacyBases: List<String>,
            legacyAffixes: List<AffixItem>,
        ): CaptionKindsRead {
            val out = mutableMapOf<CaptionKind, CaptionKindConfig>()
            // 从旧版每类自己的口癖表里并出来的条目；调用方再合到公共表上。
            val mergedAffixes = mutableListOf<AffixItem>()
            if (obj != null) {
                for (kind in CaptionKind.entries) {
                    val c = obj.optJSONObject(kind.name.lowercase()) ?: continue
                    val bases = (c.optJSONArray("bases")?.let { a ->
                        (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
                    } ?: emptyList())
                    // 旧版每类一张口癖表，现在合成一张公共的，所以读的时候把这些表并进去
                    // （按 类型名+文本+权重 去重），别让用户配过的东西在升级后凭空消失。
                    val own = c.optJSONArray("affixes")?.let { a ->
                        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { AffixItem.fromJson(it) } }
                    } ?: emptyList()
                    own.forEach { item ->
                        if (mergedAffixes.none { it == item }) mergedAffixes.add(item)
                    }
                    out[kind] = CaptionKindConfig(
                        // 认不出的档位名当没写，别整份丢掉 —— 这一类的底句表还在，
                        // 退回默认档还能接着用。有没有自己那张表按这一类自己的算，
                        // 不能用合并中的公共表，不然前面那类填过就会把后面那类也顶上 AFFIX。
                        mode = CaptionMode.fromJson(c.optString("mode", ""))
                            ?: CaptionKindConfig.migrate(
                                enabled = c.optBoolean("enabled", true),
                                randomBase = c.optBoolean("randomBase", false),
                                hadOwnList = own.isNotEmpty(),
                            ),
                        bases = bases,
                    )
                }
                if (out.isNotEmpty()) return CaptionKindsRead(out, mergedAffixes)
            }
            if (!legacyRandomBase && legacyBases.isEmpty() && legacyAffixes.isEmpty() && mode == ImageDescMode.OFF) {
                return CaptionKindsRead(emptyMap(), emptyList())
            }
            // 旧版「单独设置」那份表在别的档位下根本不读，别把它塞进去当生效值。
            val legacyDedicated = if (mode == ImageDescMode.DEDICATED) legacyAffixes.isNotEmpty() else false
            val legacy = CaptionKindConfig(
                mode = CaptionKindConfig.migrate(
                    enabled = true,
                    randomBase = legacyRandomBase,
                    hadOwnList = legacyDedicated,
                ),
                bases = legacyBases,
            )
            return CaptionKindsRead(CaptionKind.entries.associateWith { legacy }, emptyList())
        }

        /** [readCaptionKinds] 的两个结果：每类的配置，和从旧版每类口癖表里并出来的公共表条目。 */
        private class CaptionKindsRead(
            val kinds: Map<CaptionKind, CaptionKindConfig>,
            val publicAffixes: List<AffixItem>,
        )

        fun fromJson(json: String): KuchiguseConfig {
            if (json.isBlank()) return KuchiguseConfig()
            return try {
                val o = JSONObject(json)
                fun parseArray(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { a.optString(it) }
                }
                val affixes = (o.optJSONArray("affixes")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        a.optJSONObject(idx)?.let { AffixItem.fromJson(it) }
                    }
                } ?: emptyList()).ifEmpty {
                    parseArray("tics").map { AffixItem(AffixKind.SUFFIX, it, 100) }
                }
                val replacements = (o.optJSONArray("replacements")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        a.optJSONObject(idx)?.let { Replacement.fromJson(it) }
                    }
                } ?: emptyList()).ifEmpty {
                    listOf(Replacement("怎么", "怎喵"))
                }
                // 单聊、群聊各有一份「生效哪份名单」的选择，另一份照存不误。
                // 旧的 listMode / sessionWhitelist / sessionBlacklist / *ChatsOff 都不迁移：
                // 旧模型是一份名单加整类开关，新模型是两份名单各管一类，语义没法等价翻译，
                // 硬搬过去会得出和用户预期相反的结果，所以升级后按新默认值走。
                fun parseMode(key: String): ListMode =
                    if (o.optString(key, "blacklist") == "whitelist") ListMode.WHITELIST else ListMode.BLACKLIST
                val singleMode = parseMode("singleListMode")
                val groupMode = parseMode("groupListMode")
                fun parseSet(key: String): Set<String> =
                    parseArray(key).filter { it.isNotBlank() }.toSet()
                val parsedPublicAffixes = o.optJSONArray("imageDescAffixes")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        a.optJSONObject(idx)?.let { AffixItem.fromJson(it) }
                    }
                } ?: emptyList()
                val kindsRead = readCaptionKinds(
                    o.optJSONObject("imageDescKinds"),
                    mode = ImageDescMode.entries
                        .firstOrNull { it.name.equals(o.optString("imageDescMode", ""), true) }
                        ?: if (o.optBoolean("imageDescAffix", false)) ImageDescMode.MAIN else ImageDescMode.OFF,
                    legacyRandomBase = o.optBoolean("imageDescRandomBase", false),
                    legacyBases = o.optJSONArray("imageDescBases")?.let { a ->
                        (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
                    } ?: emptyList(),
                    legacyAffixes = parsedPublicAffixes,
                )
                KuchiguseConfig(
                    enabled = o.optBoolean("enabled", true),
                    probabilityExpr = o.optString("probability", "1"),
                    scope = if (o.optString("scope", "sentence") == "whole") Scope.WHOLE_MESSAGE else Scope.PER_SENTENCE,
                    affixes = affixes,
                    affixRepeatPenalty = o.optDouble("affixRepeatPenalty", 20.0)
                        .coerceIn(0.0, 99.0),
                    allowPrefixSuffixTogether = o.optBoolean("allowPrefixSuffixTogether", false),
                    panguMode = o.optString("panguMode", "").let { raw ->
                        PanguMode.entries.firstOrNull { it.name.equals(raw, true) }
                            // 老配置里没有 panguMode，只有那个 boolean：true 就是「只对接缝」
                            ?: if (o.optBoolean("affixSpaceCjkLatin", false)) PanguMode.AFFIX else PanguMode.OFF
                    },
                    affixSpaceLatinLatin = o.optBoolean("affixSpaceLatinLatin", true),
                    imageDescMode = ImageDescMode.entries
                        .firstOrNull { it.name.equals(o.optString("imageDescMode", ""), true) }
                        // v101 只有一个布尔开关：开着的按「用主表」迁移
                        ?: if (o.optBoolean("imageDescAffix", false)) ImageDescMode.MAIN else ImageDescMode.OFF,
                    // 旧版每类自己的口癖表并进公共表：表里写着的排前面，并出来的接着排，去重。
                    imageDescAffixes = (parsedPublicAffixes + kindsRead.publicAffixes).distinct(),
                    imageDescKinds = kindsRead.kinds,
                    botChinesePeriod = o.optBoolean("botChinesePeriod", false),
                    lineEndsSentence = o.optBoolean("lineEndsSentence", false),
                    affixTailTilde = o.optBoolean("affixTailTilde", false),
                    replacements = replacements,
                    ignoreUrl = o.optBoolean("ignoreUrl", true),
                    ignoreEmoji = o.optBoolean("ignoreEmoji", true),
                    ignoreRandomLike = o.optBoolean("ignoreRandom", true),
                    hideSessionNumber = o.optBoolean("hideSessionNumber", false),
                    maxLength = o.optInt("maxLength", KuchiguseConfig().maxLength),
                    lengthGradient = o.optDouble("lengthGradient", 1.0),
                    stability = o.optDouble("stability", KuchiguseConfig().stability),
                    stabilityMinGuaranteed = o.optInt("stabilityMinGuaranteed", KuchiguseConfig().stabilityMinGuaranteed),
                    lengthAutoAdjustPool = o.optBoolean("lengthAutoAdjustPool", false),
                    antithesisEnabled = o.optBoolean("antithesis", false),
                    blockKeywords = parseArray("blocks"),
                    floatEnabled = o.optBoolean("floatEnabled", true),
                    floatSizeDp = o.optInt("floatSizeDp", 20),
                    floatAlpha = o.optDouble("floatAlpha", 0.5).toFloat(),
                    singleListMode = singleMode,
                    groupListMode = groupMode,
                    singleWhitelist = parseSet("singleWhitelist"),
                    singleBlacklist = parseSet("singleBlacklist"),
                    groupWhitelist = parseSet("groupWhitelist"),
                    groupBlacklist = parseSet("groupBlacklist"),
                )
            } catch (t: Throwable) {
                // 静默回落成默认值等于「用户配置被清空」，至少要留下痕迹
                Log.w("KuchiguseConfig", "config parse failed, falling back to defaults", t)
                KuchiguseConfig()
            }
        }
    }
}