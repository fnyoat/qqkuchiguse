/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.engine

import io.github.fnyoat.qqkuchiguse.config.AffixItem
import io.github.fnyoat.qqkuchiguse.config.AffixKind
import io.github.fnyoat.qqkuchiguse.config.CaptionKind
import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig
import io.github.fnyoat.qqkuchiguse.config.PanguMode
import io.github.fnyoat.qqkuchiguse.config.Scope
import io.github.fnyoat.qqkuchiguse.util.MathExprEvaluator
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * 核心消息转换引擎：分句 → 三道概率门控（总概率池 / 句长门槛 / 对仗强制）→ 加权随机贴一个
 * 前/后缀。纯 URL、纯 emoji、疑似随机串组成的句子可配置忽略。
 */
object KuchiguseEngine {

    /**
     * 句子边界：`。（）！？…，` 加换行。**只认全角**，ASCII `,` `.` `!` `?` 不切句
     * （`hello, i love you` 一个口癖，`hello，i love you` 两个）；连续 ≥2 个 `.` / `．` 是例外，
     * 那是省略号，按 [DOT_RUN_LIMIT] 切。必须含换行：否则上一行的密钥
     * 会把后面那行正常的话一起拖下水。用 when 而非字符串常量：两张表迟早不同步，而漏一个标点
     * 会同时弄乱切句和口癖位置。
     */
    private fun isChunkBoundary(c: Char): Boolean = when (c) {
        '。', '（', '）', '！', '？', '…', '，', '\n', '\r' -> true
        else -> false
    }

    private const val DOT_RUN_LIMIT = 2

    /** 句尾波浪号。只在最后一句的口癖后面补，见 [KuchiguseConfig.affixTailTilde]。 */
    private const val TAIL_TILDE = "~"

    /**
     * 句号补在口癖**之后**（「我爱你喵。」），再加波浪号就是「我爱你喵~。」——整条收尾只容得下一
     * 个符号，所以两者互斥；同开时波浪号静默失效，界面上看不出来。
     */
    private fun tailTildeEnabled(cfg: KuchiguseConfig): Boolean =
        cfg.affixTailTilde && !cfg.botChinesePeriod

    // 宁可漏判也不能把 `Python3.11`、`e.g.` 当链接——误判会让整句被当成纯链接跳过。所以裸域名
    // 必须以字母结尾的顶级域收尾，纯数字 IP 单独放行。
    private val URL_REGEX = Regex(
        "(?i)(https?://\\S+|ftp://\\S+|www\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+|" +
            "\\b[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}\\b|" +
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b)"
    )

    /**
     * 图片 / 表情 / 平底锅的外显描述。返回要写进字段的整串，null 表示这个元素不该动。
     *
     * 就是「底句 + 口癖」（`[图片] 喵`）。底句由调用方给：元素上已经有自己的说明就用那一句，
     * 没有就取 QQ 自己那句默认占位符（见 `QqPlaceholder`）——**不是**写死的 `image`，也不是
     * 文件名，QQ 从来不显示文件名。用户能改的只有后面接什么。
     *
     * 抽签没中就返回 null，也就是**一个字都不写**：底句保持 QQ 自己的（或者用户自己写的那句），
     * 不该因为没抽到口癖就把底句换成别的。
     *
     * 幂等：sendMsg 在主进程和子进程都 hook，同一张图会被处理不止一次，所以
     * [origin] 里已经接过口癖就放过（见 [alreadyGlued]），不会叠成「喵喵」。
     *
     * 这里**不**走 [process]：那套会替换词、补句号、判对仗、截长度，每一样作用在这一行字上都是
     * 错的。抽签闸门仍然共用（见 [drawCaptionAffix]），所以概率、名单、屏蔽词跟发消息时是
     * 一个口径。
     *
     * [kind] 决定这一类自己怎么配：关掉没有、底句换不换、口癖用哪张表，见
     * [KuchiguseConfig.captionEnabled] / [KuchiguseConfig.affixesFor]。图片、动画表情、普通表情、
     * 表情泡泡是四类，各选各的档位；档位选「用公共口癖表」的四类共用一张表，不是各自一份。
     */
    fun imageCaption(cfg: KuchiguseConfig, kind: CaptionKind, base: String, origin: String?): String? {
        if (!cfg.captionEnabled(kind)) return null
        if (base.isBlank()) return null
        if (hitsBlockKeyword(base, cfg) || (!origin.isNullOrEmpty() && hitsBlockKeyword(origin, cfg))) return null
        val list = cfg.affixesFor(kind)
        if (!origin.isNullOrEmpty() && alreadyGlued(origin, list)) return null
        val affix = drawCaptionAffix(list, cfg, base) ?: return null
        glueInsideBrackets(base, affix)?.let { return it }
        return glueAffix(
            base,
            affix.text,
            atEnd = affix.kind == AffixKind.SUFFIX,
            spaceCjkLatin = cfg.panguMode != PanguMode.OFF,
            spaceLatinLatin = cfg.affixSpaceLatinLatin,
        )
    }

    /**
     * 图片/表情的随机底句：抽中就返回那句话，没抽中返回 null，让调用方退回 QQ 自己那句。
     *
     * [gateText] 是这条消息当前的底句（QQ 那句占位符，或元素上自己写的说明），闸门就拿它来判，
     * 跟 [imageCaption] 判的是同一句，所以屏蔽词、概率是一个口径：把「图片」屏蔽了，两边都不动。
     *
     * 刻意不调 [pruneAffixMisses]：那份计数是按整张表 retain 的，拿底句这张临时表去裁会把
     * 口癖的计数全删掉。记账键是 kind+文本，底句表用完即弃也不影响它跨发送累积。
     *
     * 模块总开关和这一类自己的开关都在这里先判一遍：底句抽出来最后还是要过 [imageCaption]
     * 才写得进去，先判是省掉一次白抽，语义上也跟写入口径一致。
     *
     * 底句和口癖是两次独立抽签：这里抽底句，[imageCaption] 再抽口癖，两次都过才既换底句又
     * 带口癖；底句没抽中时口癖仍可能接在 QQ 那句上，所以「出字」的概率不等于单看这处。
     */
    fun randomCaptionBase(cfg: KuchiguseConfig, kind: CaptionKind, gateText: String): String? {
        if (!cfg.enabled) return null
        // 只有「随机底句（整条消息）」这一档才抽，其余三档底句照 QQ 原样
        if (!cfg.captionRandomBase(kind)) return null
        if (hitsBlockKeyword(gateText, cfg)) return null
        val pool = cfg.basesFor(kind).mapNotNull { t -> t.trim().takeIf { it.isNotEmpty() } }
        if (pool.isEmpty()) return null
        val items = pool.map { AffixItem(AffixKind.SUFFIX, it, 100) }
        val plan = ChunkPlan(
            raw = gateText,
            text = gateText.trim(),
            forced = false,
            ignored = false,
            dots = 0.0,
            operable = true,
            lenProb = cfg.lengthProbability(gateText.length),
        )
        val plans = listOf(plan)
        finishPlans(plans, cfg, poolProbability(cfg, plans, gateText))
        if (!shouldProcess(plan)) return null
        val picked = pickForMessage(items, cfg, null)?.first?.text?.takeIf { it.isNotEmpty() } ?: return null
        // 抽中的那句自己命中屏蔽词就不用了，退回 QQ 那句，而不是把屏蔽词发出去。
        return if (hitsBlockKeyword(picked, cfg)) null else picked
    }

    /**
     * 屏蔽词判定，[process] 和外显描述共用一份，免得两处口径不一样。
     *
     * 每条消息都会过这里，所以走下标而不是 `any {}`：后者对 List 也要先 `iterator()`，等于
     * 每条消息白扔一个迭代器（blockKeywords 为空时也是）。
     */
    private fun hitsBlockKeyword(text: String, cfg: KuchiguseConfig): Boolean {
        val words = cfg.blockKeywords
        for (i in words.indices) {
            val w = words[i]
            if (w.isNotEmpty() && text.contains(w)) return true
        }
        return false
    }

    /**
     * 括号占位符把口癖塞进括号里：`[图片]` 接 `喵` 得 `[图片喵]`，不是 `[图片]喵`。
     *
     * QQ 那句默认描述整个就是一对括号，括号外再挂东西看着像多说了半句。凑不齐成对的 `[...]`
     * 就返回 null，退回 [glueAffix] 按原样接在后面，不硬塞。
     */
    private fun glueInsideBrackets(base: String, affix: AffixItem): String? {
        val text = affix.text
        if (text.isEmpty()) return null
        val body = base.trim()
        if (body.length < 2 || !body.startsWith("[") || !body.endsWith("]")) return null
        return if (affix.kind == AffixKind.PREFIX) {
            "[" + text + body.substring(1)
        } else {
            body.substring(0, body.length - 1) + text + "]"
        }
    }

    /**
     * 字段里是不是已经接过口癖了。sendMsg 在主进程和子进程都被 hook，同一张图会被处理不止一次，
     * 第二次读到的值已经带着口癖，不挡住就会叠成「[图片喵]喵」。
     *
     * 塞进括号之后整句以 `]` 收尾，所以括号内也要认，只看结尾会漏。
     */
    private fun alreadyGlued(origin: String, items: List<AffixItem>): Boolean {
        if (origin.isEmpty()) return false
        val body = origin.trim()
        val close = body.lastIndexOf(']')
        val inner = if (body.startsWith("[") && close > 0) body.substring(1, close) else null
        return items.any { a ->
            val t = a.text
            if (t.isEmpty()) return@any false
            val atStart = a.kind == AffixKind.PREFIX
            if (inner != null) {
                if (atStart) inner.startsWith(t) else inner.endsWith(t)
            } else if (atStart) {
                body.startsWith(t)
            } else {
                body.endsWith(t)
            }
        }
    }

    /**
     * 抽一个口癖，闸门跟发送时是同一套：概率池 → 保底名额 → 掷骰子 → 记账
     * （[poolProbability] / [finishPlans] / [shouldProcess] / [pickForMessage]）。
     * 少了的只有替换、补句号、对仗、截长度那几步 —— 它们对一行描述没有意义。
     *
     * 刻意**不**走 [isIgnorable]：那一套是给正文用的「整句只是个链接就别碰」，而这里算的是
     * 一行短标签，判成不可处理只会让这个开关白开。
     */
    private fun drawCaptionAffix(items: List<AffixItem>, cfg: KuchiguseConfig, base: String): AffixItem? {
        if (items.isEmpty()) return null
        pruneAffixMisses(items)
        val text = base.trim()
        val plan = ChunkPlan(
            raw = base,
            text = text,
            forced = false,
            ignored = false,
            dots = 0.0,
            operable = true,
            lenProb = cfg.lengthProbability(text.length),
        )
        val plans = listOf(plan)
        finishPlans(plans, cfg, poolProbability(cfg, plans, base))
        if (!shouldProcess(plan)) return null
        return pickForMessage(items, cfg, null)?.first?.takeIf { it.text.isNotEmpty() }
    }

    /**
     * 处理发送文本。返回 null 表示保持原样。
     *
     * [PanguMode.WHOLE_MESSAGE] 的整条扫描放在最外层，跟 [botChinesePeriod] 一样独立于
     * 抽签闸门：闸门没掷中、一个口癖都没配、甚至整段消息只被补了个句号，该补的空格照样补。
     */
    fun process(text: String, cfg: KuchiguseConfig): String? {
        val trimmed = text.trim()
        // 禁用、空消息、命中屏蔽词：整条不许动，盘古也不能碰。抽签没中那种「处理了但等于
        // 没处理」不算这种，照样要补空格——所以这个闸门跟 [refine] 分开写。
        if (!cfg.enabled || trimmed.isEmpty()) return null
        if (hitsBlockKeyword(trimmed, cfg)) return null

        val refined = refine(trimmed, cfg)
        val out = if (cfg.panguMode == PanguMode.WHOLE_MESSAGE) panguSpaces(refined, cfg) else refined
        return if (out == trimmed) null else out
    }

    private fun refine(trimmed: String, cfg: KuchiguseConfig): String {
        // 补句号和口癖抽签是两件独立的事，先补句号，后面口癖照常贴在这个基础上。
        // 抽签没中、或者压根没配口癖时，也不能把句号还回去。
        val withPeriod = if (cfg.botChinesePeriod) addChinesePeriod(trimmed, cfg) else trimmed
        val periodOnly = if (withPeriod != trimmed) withPeriod else null

        // 一个口癖都没配的话，下面的分句、分类、算概率全是白做：不管闸门怎么掷，最后
        // 返回的都是 periodOnly，没补过句号就是原文。所以这个检查必须排在闸门之前。
        val affixList = cfg.affixes.takeIf { it.isNotEmpty() } ?: return periodOnly ?: trimmed
        pruneAffixMisses(affixList)

        // Gate 1: 总概率池。强制对仗的句子绕过此门
        val plans = classifyChunks(splitSentences(trimmed), cfg)
        // 走下标而不是 `any { it.forced }`：这条每条消息都跑，后者每次分配一个迭代器。
        var hasForcedCouplet = false
        for (i in plans.indices) if (plans[i].forced) { hasForcedCouplet = true; break }
        val poolProb = poolProbability(cfg, plans, trimmed, hasForcedCouplet)
        val dotted = finishPlans(plans, cfg, poolProb)

        if (!hasForcedCouplet) {
            if (Random.nextDouble() >= poolProb) return periodOnly ?: trimmed
        }

        // 补过句号的话句读边界可能变，得以新文本重新分句、重新分类；没补（默认配置）就
        // 沿用上面那份，chunk 列表逐项相等。两条路给 applyPerSentence 的东西是一样的。
        val finalPlans = if (withPeriod === trimmed) {
            dotted
        } else {
            finishPlans(classifyChunks(splitSentences(withPeriod), cfg), cfg, poolProb)
        }

        val result = when (cfg.scope) {
            Scope.PER_SENTENCE -> applyPerSentence(affixList, cfg, finalPlans, drawPerSentence = true)
            Scope.WHOLE_MESSAGE -> applyPerSentence(affixList, cfg, finalPlans, drawPerSentence = false)
        }
        return result ?: periodOnly ?: trimmed
    }

    /**
     * 挑一个 affix，并按 [KuchiguseConfig.allowPrefixSuffixTogether] 决定是否锁定类型。返回值第二项
     * 是本次锁定的类型：关闭时整段消息共用第一次抽到的类型（老约束「前后缀不同时出现」仍成立），
     * 打开则不锁定。null 表示尚未锁定。
     */
    private fun pickForMessage(
        items: List<AffixItem>,
        cfg: KuchiguseConfig,
        lockedKind: AffixKind?,
    ): Pair<AffixItem, AffixKind?>? {
        // 类型锁定时把 onlyKind 传下去让抽取自己筛，而不是先 `filter` 出一个新列表：这一句
        // 每句都调，原写法每句新建一个 ArrayList 外加它的迭代器。空集兜底（该类型一个都没有就
        // 退回全部候选）要真知道有没有匹配的，扫一遍比建列表便宜且不分配。
        val onlyKind = if (cfg.allowPrefixSuffixTogether || lockedKind == null) {
            null
        } else if (hasKind(items, lockedKind)) {
            lockedKind
        } else {
            null
        }
        val picked = pickAffix(items, cfg.affixRepeatPenalty, onlyKind) ?: return null
        val nextLocked = if (cfg.allowPrefixSuffixTogether) null else (lockedKind ?: picked.kind)
        return picked to nextLocked
    }

    /** 有没有这个类型的口癖。走下标遍历，`any {}` 对 List 也会取迭代器。 */
    private fun hasKind(items: List<AffixItem>, kind: AffixKind): Boolean {
        for (i in items.indices) if (items[i].kind == kind) return true
        return false
    }

    /**
     * 总概率池：先读配置，密集时再自动压一档。发送和预览必须算出同一个数，否则预览上标的百分比和
     * 实际抽签对不上。
     */
    private fun poolProbability(
        cfg: KuchiguseConfig,
        plans: List<ChunkPlan>,
        trimmed: String,
        hasForcedCouplet: Boolean = false,
    ): Double {
        var prob = parseProbability(cfg.probabilityExpr)
        if (!cfg.lengthAutoAdjustPool) return prob
        if (hasForcedCouplet) return prob
        var operable = 0
        for (p in plans) if (p.operable) operable++
        if (operable < 5) return prob
        val totalChars = trimmed.length
        val expectedOps = prob * operable
        if (totalChars > 0 && expectedOps / totalChars >= 0.4) prob *= 0.8
        return prob
    }

    /** 一个 chunk 的全部排版结论。[dots] 可变，好让 [finishPlans] 就地填概率。 */
    private class ChunkPlan(
        val raw: String,
        val text: String,
        val forced: Boolean,
        val ignored: Boolean,
        var dots: Double = 0.0,
        /** 纯标点块没有正文可贴，折进 [operable]；算一次存下来，别在三处重复逐字符判断。 */
        val operable: Boolean = false,
        /** `cfg.lengthProbability(句长)`，以前在同一句上算三遍（eligible、dots、保底排序）。 */
        val lenProb: Double = 0.0,
    )

    /**
     * 每个 chunk 的分类，**整个流水线只跑一遍**（isCoupletLike 要数汉字、isIgnorable 要跑 URL 正则，
     * 都不便宜）。统一在 trim 过的句身上判定：这两个判定对首尾空白都不敏感。
     */
    private fun classifyChunks(chunks: List<String>, cfg: KuchiguseConfig): List<ChunkPlan> {
        val plans = ArrayList<ChunkPlan>(chunks.size)
        for (raw in chunks) {
            val text = raw.trim()
            val forced = cfg.antithesisEnabled && isCoupletLike(text)
            val ignored = !forced && isIgnorable(text, cfg)
            plans.add(
                ChunkPlan(
                    raw = raw,
                    text = text,
                    forced = forced,
                    ignored = ignored,
                    dots = 0.0,
                    operable = !forced && !ignored && !isPunctPlaceholder(text),
                    lenProb = cfg.lengthProbability(text.length),
                ),
            )
        }
        return plans
    }

    /**
     * 填命中概率和保底名额。必须整段跑在 [shouldProcess] 的掷骰子之前——中间插一次随机调用会让预览
     * 抽到和实际发送不同的口癖。名额按 `guaranteeCap` 封顶（句长够得着 `maxLength`），否则短句也
     * 保底，看着像「每句都加喵」。
     */
    private fun finishPlans(
        plans: List<ChunkPlan>,
        cfg: KuchiguseConfig,
        poolProb: Double,
    ): List<ChunkPlan> {
        var operableCount = 0
        for (p in plans) if (p.operable) operableCount++
        if (operableCount == 0) {
            for (p in plans) p.dots = if (p.forced) 1.0 else 0.0
            return plans
        }

        // 稳定性 = stability × 可操作句数 + minGuaranteed。stability <= 0 表示关掉，只留保底数。
        val expectedOps = poolProb * operableCount
        val stability = if (cfg.stability.isFinite() && cfg.stability > 0) cfg.stability else 0.0
        // 保底按「够长」的句子数封顶：长度概率打满（字数 >= maxLength）才配拿保底。一条都够不着时
        // 留 1 个，保证消息不会被整条跳过——否则 maxLength=10 时 6 个字的中文短句也会保底。
        var eligible = 0
        for (p in plans) if (p.operable && p.lenProb >= 1.0) eligible++
        val guaranteeCap = maxOf(eligible, 1)
        val guaranteedCount = ((expectedOps * stability).roundToInt() + cfg.stabilityMinGuaranteed)
            .coerceAtMost(operableCount)
            .coerceAtMost(guaranteeCap)

        for (p in plans) {
            p.dots = if (p.forced) 1.0 else if (p.operable) poolProb * p.lenProb else 0.0
        }
        if (guaranteedCount <= 0) return plans
        // 保底名额按长度概率从高到低给，只把 dots 提成 1.0。**按下标排，不按 raw 排**：
        // 用 raw 建集合会把内容相同的重复 chunk 一次性全标上，可能超过名额数。
        val order = ArrayList<Int>(operableCount)
        for (i in plans.indices) if (plans[i].operable) order.add(i)
        order.sortByDescending { plans[it].lenProb }
        for (k in 0 until guaranteedCount) plans[order[k]].dots = 1.0
        return plans
    }

    private fun shouldProcess(plan: ChunkPlan): Boolean {
        if (plan.forced) return true
        if (!plan.operable) return false
        return plan.dots > 0 && Random.nextDouble() < plan.dots
    }

    /**
     * 逐句贴口癖。[drawPerSentence] 决定「在哪抽」：true 每句各抽一次，false 整条抽一次。两种都是
     * 逐句贴，区别只在抽签次数。句身由 [plans] 带入，不要在这里重切。
     */
    private fun applyPerSentence(
        items: List<AffixItem>,
        cfg: KuchiguseConfig,
        plans: List<ChunkPlan>,
        drawPerSentence: Boolean,
    ): String {
        val sb = StringBuilder()
        // 类型锁定：默认整段消息只用一种（沿用「前后缀不同时出现」），每句更新一次。
        var lockedKind: AffixKind? = null
        var shared: AffixItem? = null
        // 波浪号挂在**最后一句能贴口癖的话**后面：从后往前找第一个 operable chunk。消息以空行收尾
        // 时 plans.last 不可操作，挂上去就跑到换行另一边了。找不到就整条不加。
        var tildeAt = -1
        for (i in plans.indices.reversed()) {
            if (plans[i].operable) { tildeAt = i; break }
        }
        val tilde = tildeAt >= 0 && tailTildeEnabled(cfg)
        for (i in plans.indices) {
            val plan = plans[i]
            // 切出来的每一句都带着自己的分隔符（换行、句读），句身之外的前后空白必须
            // 原样接回去。尾部漏掉的话多行消息会被粘成一行，口癖也贴到了换行的另一边。
            val rawSentence = plan.raw
            if (!shouldProcess(plan)) {
                sb.append(rawSentence)
                continue
            }
            val affix = if (drawPerSentence) {
                // 每一句单独抽签。
                val drawn = pickForMessage(items, cfg, lockedKind)
                if (drawn == null || drawn.first.text.isEmpty()) {
                    sb.append(rawSentence)
                    continue
                }
                lockedKind = drawn.second
                drawn.first
            } else {
                val existing = shared
                if (existing != null) {
                    existing
                } else {
                    val drawn = pickForMessage(items, cfg, null)
                    if (drawn == null || drawn.first.text.isEmpty()) {
                        sb.append(rawSentence)
                        continue
                    }
                    shared = drawn.first
                    drawn.first
                }
            }
            sb.append(rewriteSentence(rawSentence, affix, cfg, tailTilde = tilde && i == tildeAt))
        }
        return sb.toString()
    }

    private fun isPunctPlaceholder(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        // 字符集逐字照抄设计时的集合，一字不多——扩成区间会把全角字母数字（Ａ、０）也判成纯标点。
        // 判断用 when（跳转表）而非 `c in "..."`（String.contains(Char)，每字符扫一遍字面量）。
        for (c in t) {
            val ok = when (c) {
                '。', '！', '？', '…', '（', '）', ' ', '「', '」', '、', '，', '；' -> true
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    // 句尾标点字符表，与原来正则 `[。！？…，（）.．]+[ \t]*$` 里的字符集逐字一致
    // （注意含半角 `.`，全角 `．` 也在内）。判断用 when（跳转表）而非 `c in "..."`。
    private fun isTrailingPunct(c: Char): Boolean = when (c) {
        '。', '！', '？', '…', '（', '）', '，', '.', '．' -> true
        else -> false
    }

    private fun isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'

    private fun isLineTerminator(c: Char): Boolean =
        c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    /** 末尾那一个行终止符有多长（0/1/2），没有就是 0。`\r\n` 算两个，且只认这个顺序。 */
    private fun finalTerminatorLen(s: String): Int {
        val n = s.length
        if (n >= 2 && s[n - 2] == '\r' && s[n - 1] == '\n') return 2
        if (n > 0 && isLineTerminator(s[n - 1])) return 1
        return 0
    }

    /**
     * `[punct]+[ \t]*` 能不能正好收在 [anchor]，能就返回起点，不能返回 -1。
     *
     * 两个字符集不相交，所以 [anchor] 往前扫出来的那段只能是「一串标点 + 一串空白/制表符」，
     * 顺序由正则定死了，反过来不成立（`"。 "` 不匹配，因为标点必须在空白前面）。开头是空的就
     * 说明没有标点，正则的 `+` 要求至少一个。
     */
    private fun punctRunEndingAt(s: String, anchor: Int): Int {
        var i = anchor
        while (i > 0 && isSpaceOrTab(s[i - 1])) i--
        var j = i
        while (j > 0 && isTrailingPunct(s[j - 1])) j--
        return if (j == i) -1 else j
    }

    /**
     * 句尾那段标点（连带其后的空白/制表符），一个都没有就是空串。等价于
     * `[。！？…，（）.．]+[ \t]*$` 在 s 上的首个匹配。
     *
     * 原来这里直接跑正则，而它没有字面前缀，每个字符位置都要试一遍，还每次 new 一个 Matcher。
     * 句子级的热点（每句都要调一次），中文消息几乎句句带标点，命中率还高。
     *
     * `$` 的坑在 [finalTerminatorLen]：它不止锚在文本末尾，还锚在**最后一个行终止符之前**，
     * 而那个终止符最多只有一个。所以候选锚点只有两个 —— 末尾、以及末尾减去终止符长度；
     * 先试末尾（更靠右的锚点，匹配起点也更靠右，但文本末尾合法时 `$` 一定认它）。
     * 混合终止符（`"\n\r"`）不是 `\r\n`，只按单个算，因此不匹配。
     *
     * 逐字符 + 2/3 字组合 + 80 万随机串与原正则比对，全部一致。
     */
    private fun trailingPunctFast(s: String): String {
        val len = s.length
        val atEnd = punctRunEndingAt(s, len)
        if (atEnd >= 0) return s.substring(atEnd, len)
        val term = finalTerminatorLen(s)
        if (term > 0) {
            val anchor = len - term
            val beforeTerm = punctRunEndingAt(s, anchor)
            if (beforeTerm >= 0) return s.substring(beforeTerm, anchor)
        }
        return ""
    }

    /**
     * 假装机器人（中文）：末尾没标点就补一个句号。[cfg.lineEndsSentence] 关闭时只看整条最后一句
     * （「你好。今天很好」→「你好。今天很好。」，句读处本来就带标点）；打开时下一个换行就算句尾。
     * 末尾已有任何标点（含逗号分号）不动，末尾是链接或密钥也不动（加句号会改坏 URL）。
     */
    private fun addChinesePeriod(text: String, cfg: KuchiguseConfig): String {
        if (text.isBlank()) return text
        if (!cfg.lineEndsSentence) return addPeriodToLine(text, cfg)
        val sb = StringBuilder(text.length + 8)
        var lineStart = 0
        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
            sb.append(addPeriodToLine(text.substring(lineStart, lineEnd), cfg))
            if (lineEnd >= text.length) break
            if (text[lineEnd] == '\r' && lineEnd + 1 < text.length && text[lineEnd + 1] == '\n') {
                sb.append("\r\n")
                lineStart = lineEnd + 2
            } else {
                sb.append(text[lineEnd])
                lineStart = lineEnd + 1
            }
        }
        return sb.toString()
    }

    /** 给一行（或整条消息）的末尾补句号，条件不够就原样返回。 */
    private fun addPeriodToLine(line: String, cfg: KuchiguseConfig): String {
        if (line.isBlank()) return line
        var end = line.length
        while (end > 0 && line[end - 1].isWhitespace()) end--
        if (end == 0) return line
        // 末尾不是字（字母、数字、汉字）就说明用户已经敲了标点，或者收在表情、
        // 引号、括号上——这些都不该再补，不然会出现「你好，。」这种。
        if (!line[end - 1].isLetterOrDigit()) return line
        val head = line.substring(0, end)
        if (isIgnorable(head, cfg)) return line
        if (endsProtected(head, cfg)) return line
        return head + "。" + line.substring(end)
    }

    /**
     * 口癖与正文之间要不要补空格，只在贴口癖的那一个接缝上调用。中文↔英文看 [spaceCjkLatin]，英文↔英文
     * 看 [spaceLatinLatin]；中文↔中文两边都不管。任一边已是空白或标点就不补。
     */
    private fun needsSpace(
        left: Char,
        right: Char,
        spaceCjkLatin: Boolean,
        spaceLatinLatin: Boolean,
    ): Boolean {
        if (left.isWhitespace() || right.isWhitespace()) return false
        if (!left.isLetterOrDigit() || !right.isLetterOrDigit()) return false
        val leftCjk = isCjkChar(left)
        val rightCjk = isCjkChar(right)
        if (leftCjk != rightCjk) return spaceCjkLatin
        if (!leftCjk) return spaceLatinLatin
        return false
    }

    /**
     * 整条消息扫一遍，汉字和西文相接处补空格（「这是QQ消息」→「这是 QQ 消息」）。
     *
     * 两边都是汉字不补（「你好喵」），有一边是标点、空白、emoji 也不补（「你好，喵」）。
     * 链接整段跳过：开了忽略链接时，`example.com中文` 这种贴着域名的中文不能被拆开，
     * 否则用户看到的是坏掉的网址。
     */
    private fun panguSpaces(text: String, cfg: KuchiguseConfig): String {
        if (text.length < 2) return text
        val inUrl = BooleanArray(text.length)
        if (cfg.ignoreUrl && mayContainUrl(text)) {
            for (m in URL_REGEX.findAll(text)) {
                for (i in m.range) inUrl[i] = true
            }
        }
        val sb = StringBuilder(text.length + 8)
        sb.append(text[0])
        for (i in 1 until text.length) {
            if (!inUrl[i] && !inUrl[i - 1] && panguNeedsSpace(text[i - 1], text[i])) sb.append(' ')
            sb.append(text[i])
        }
        return sb.toString()
    }

    private fun panguNeedsSpace(left: Char, right: Char): Boolean {
        if (left.isWhitespace() || right.isWhitespace()) return false
        val leftCjk = isCjkChar(left)
        if (leftCjk == isCjkChar(right)) return false
        return (if (leftCjk) right else left).isLetterOrDigit()
    }

    /** 汉字与假名，不含中文标点。 */
    private fun isCjkChar(c: Char): Boolean {
        val cp = c.code
        return cp in 0x3040..0x30FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF
    }

    /** 把 [affix] 接到 [text] 的 [atEnd]，按两个开关各管一种接缝补空格。 */
    private fun glueAffix(
        text: String,
        affixText: String,
        atEnd: Boolean,
        spaceCjkLatin: Boolean,
        spaceLatinLatin: Boolean,
    ): String {
        if (text.isEmpty() || affixText.isEmpty()) return if (atEnd) text + affixText else affixText + text
        val (left, right) = if (atEnd) {
            text[text.length - 1] to affixText[0]
        } else {
            affixText[affixText.length - 1] to text[0]
        }
        val spaced = needsSpace(left, right, spaceCjkLatin, spaceLatinLatin)
        return when {
            atEnd && spaced -> "$text $affixText"
            atEnd -> text + affixText
            spaced -> "$affixText $text"
            else -> affixText + text
        }
    }

    /**
     * 这句话是不是已经贴过口癖了。**两端都要看**，只查一端的话第一遍贴前缀、第二遍抽到后缀就变成
     * 一句话前后各挂一个。**对所有已配置口癖查**，不只这次抽中的——sendMsg 在主进程和子进程都被
     * hook，同一条消息会被处理不止一次。
     */
    private fun alreadyGlued(sentence: String, body: String, cfg: KuchiguseConfig): Boolean {
        if (sentence.isEmpty()) return false
        for (a in cfg.affixes) {
            val t = a.text
            if (t.isEmpty()) continue
            if (sentence.startsWith(t) || body.endsWith(t)) return true
        }
        return false
    }

    /**
     * 把一条预览切成 前导空白 / 句身 / 尾部空白。界面必须先摘出这三段再拼，否则「%」会被拼到换行
     * 之后、显示到下一行，口癖也会因为尾巴挂着换行而定位不到。
     */
    fun splitChunkWhitespace(raw: String): Triple<String, String, String> {
        val bodyStart = raw.indexOfFirst { !it.isWhitespace() }
        val bodyEnd = raw.indexOfLast { !it.isWhitespace() } + 1
        if (bodyStart < 0 || bodyEnd <= bodyStart) return Triple(raw, "", "")
        return Triple(raw.substring(0, bodyStart), raw.substring(bodyStart, bodyEnd), raw.substring(bodyEnd))
    }

    /**
     * 把 [affix] 贴到 [raw] 上，句读之外的前后空白留在原位（切出来的每句都带着自己的分隔符，
     * 只有句身该被改写）。真实发送和预览都走这一个函数。
     *
     * [tailTilde] 为真时在口癖后补波浪号：只有最后一句会传 true，且必须真的贴上了口癖才传 ——
     * 波浪号跟着口癖，没中签的句子不加。
     */
    private fun rewriteSentence(
        raw: String,
        affix: AffixItem?,
        cfg: KuchiguseConfig,
        tailTilde: Boolean = false,
    ): String {
        val bodyStart = raw.indexOfFirst { !it.isWhitespace() }
        val bodyEnd = raw.indexOfLast { !it.isWhitespace() } + 1
        if (bodyStart < 0 || bodyEnd <= bodyStart) return raw
        if (affix == null) return raw
        val body = raw.substring(bodyStart, bodyEnd)
        return raw.substring(0, bodyStart) + transformSentence(body, affix, cfg, tailTilde) + raw.substring(bodyEnd)
    }

    private fun transformSentence(
        sentence: String,
        affix: AffixItem,
        cfg: KuchiguseConfig,
        tailTilde: Boolean,
    ): String {
        var s = applyReplacements(sentence, cfg)
        // 句尾标点/emoji/正文三者都只和 s 有关，算一次往下传。
        val trail = trailingPunctFast(s)
        val bodyWithEmoji = s.removeSuffix(trail)
        val emojiTail = trailingEmojiRun(bodyWithEmoji)
        val body = bodyWithEmoji.removeSuffix(emojiTail)
        if (alreadyGlued(s, body, cfg)) return s
        if (affix.kind == AffixKind.PREFIX) {
            // 句首就是链接或密钥的话，前缀贴上去等于改写了它，这个句子放弃。
            if (startsProtected(s, cfg)) return s
            val glued = glueAffix(
                s,
                affix.text,
                atEnd = false,
                spaceCjkLatin = cfg.panguMode != PanguMode.OFF,
                spaceLatinLatin = cfg.affixSpaceLatinLatin,
            )
            // 前缀在句首，「紧跟口癖」没意义，所以波浪号落在句尾。
            return if (tailTilde) glued + TAIL_TILDE else glued
        }
        // 同理：句末正好是链接或密钥，后缀不能贴上去。
        if (endsProtected(body, cfg)) return s
        // 波浪号紧跟口癖，也就是在句尾标点和 emoji 前面。用户自己敲的标点、表情留在原位。
        val glued = glueAffix(
            body,
            affix.text,
            atEnd = true,
            spaceCjkLatin = cfg.panguMode != PanguMode.OFF,
            spaceLatinLatin = cfg.affixSpaceLatinLatin,
        )
        return glued + (if (tailTilde) TAIL_TILDE else "") + emojiTail + trail
    }

    fun trailingEmojiRun(text: String): String {
        var end = text.length
        while (end > 0) {
            val ch = text[end - 1]
            val cp = if (Character.isLowSurrogate(ch) && end >= 2 && Character.isHighSurrogate(text[end - 2])) {
                Character.toCodePoint(text[end - 2], ch)
            } else ch.code
            if (!isEmojiCodePoint(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(end)
    }

    private fun emojiRunEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isEmojiCodePoint(cp)) break
            i += Character.charCount(cp)
            while (i < text.length) {
                val n = text.codePointAt(i)
                if (n == 0xFE0F || n == 0xFE0E || n == 0x200D || n in 0x1F3FB..0x1F3FF) {
                    i += Character.charCount(n)
                } else break
            }
        }
        return i
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        return (cp in 0x1F000..0x1FAFF) ||
            (cp in 0x2600..0x27BF) ||
            (cp in 0x2B00..0x2BFF) ||
            (cp in 0x1F1E6..0x1F1FF) ||
            (cp in 0x1F3FB..0x1F3FF) ||
            cp == 0xFE0F || cp == 0xFE0E ||
            cp == 0x00A9 || cp == 0x00AE
    }

    private fun applyReplacements(text: String, cfg: KuchiguseConfig): String {
        var s = text
        for (r in cfg.replacements) if (r.from.isNotEmpty()) s = s.replace(r.from, r.to)
        return s
    }

    /** 句尾标点串（供设置页实时检测器定位概率标记位置） */
    fun trailingPunct(s: String): String = trailingPunctFast(s)

    /**
     * 分句。边界：。！？…，全角括号、连续点号（≥2）、emoji 串后有文字。emoji 留在左侧，使后缀落在
     * emoji 之前。
     */
    fun splitSentences(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val curBuf = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (isEmojiCodePoint(text.codePointAt(i))) {
                val runEnd = emojiRunEnd(text, i)
                val endsChunk = runEnd < text.length && !isChunkBoundary(text[runEnd])
                if (endsChunk && curBuf.isNotEmpty()) {
                    curBuf.append(text, i, runEnd)
                    chunks.add(curBuf.toString())
                    curBuf.setLength(0)
                    i = runEnd
                    continue
                }
            }
            val c = text[i]
            if (c == '.' || c == '．') {
                var j = i
                while (j < text.length && (text[j] == '.' || text[j] == '．')) j++
                if (j - i >= DOT_RUN_LIMIT) {
                    curBuf.append(text, i, j)
                    if (curBuf.isNotEmpty()) { chunks.add(curBuf.toString()); curBuf.setLength(0) }
                    i = j
                    continue
                }
                curBuf.append(c)
                i++
                continue
            }
            if (isChunkBoundary(c)) {
                curBuf.append(c)
                if (curBuf.isNotEmpty()) { chunks.add(curBuf.toString()); curBuf.setLength(0) }
            } else {
                curBuf.append(c)
            }
            i++
        }
        if (curBuf.isNotEmpty()) chunks.add(curBuf.toString())
        return chunks
    }

    /**
     * 概率表达式求值，夹在 0..1，按表达式字符串缓存（一条消息处理中配置不会变）。表达式是用户手写的，
     * 两件事要兜住：算错（除零、括号不配对）退回 1.0，不悄悄变成 100%；夹到 1 之后检测器至少显示
     * 真实的 100%，而不是一路传下去的 5000%。
     */
    private fun parseProbability(expr: String): Double {
        if (expr.isBlank()) return 1.0
        val key = expr.trim()
        lastProb.get()?.let { if (it.expr == key) return it.value }
        val v = try { MathExprEvaluator.evaluate(key) } catch (_: Throwable) { 1.0 }
        val clamped = v.coerceIn(0.0, 1.0)
        lastProb.set(ProbCache(key, clamped))
        return clamped
    }

    private data class ProbCache(val expr: String, val value: Double)

    // 一次求值结果必须整体可读：expr 和 value 分成两个字段的话，两个线程交叉写就会
    // 读到「A 的表达式配 B 的值」，概率凭空变一次。
    private val lastProb = java.util.concurrent.atomic.AtomicReference<ProbCache?>(null)

    /** 每个 affix 连续没被选中的次数。key 是 kind+text，配置里删掉的残留计数会在下次抽取时清掉。 */
    private val affixMisses = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun affixKey(a: AffixItem): String = "${a.kind}:${a.text}"

    /** 上一次真正抽中的那条，[pickAffix] 记账时写。[pickWeighted] 只读它，不遍历 affixMisses。 */
    @Volatile
    private var lastPicked: String? = null

    /** 连续 [AFFIX_STARVE_THRESHOLD] 次没被选中后，进入「饿了就猛涨」的那一段。 */
    private const val AFFIX_STARVE_THRESHOLD = 5

    /**
     * 加权随机选一个前/后缀（互斥）。刚被选中的按 [repeatPenaltyPercent] 降权；连续没被选中的按饿死
     * 次数涨权，未达 [AFFIX_STARVE_THRESHOLD] 时 +25%/次，达到后 +200%/次。权重现算，下限 1 保证
     * 只有一个 affix、或降权到 0 时也挑得出东西。
     *
     * [onlyKind] 非空时只在该类型里挑，权重置 0 的条目等价于不在候选集里（抽签循环减到负数就
     * 返回，所以永远选不到它们）。这样 [pickForMessage] 就不必每句先 `filter` 出一个新列表。
     */
    fun pickWeighted(
        items: List<AffixItem>,
        repeatPenaltyPercent: Double = 0.0,
        onlyKind: AffixKind? = null,
    ): AffixItem? {
        if (items.isEmpty()) return null
        val penalty = (repeatPenaltyPercent / 100.0).coerceIn(0.0, 0.99)
        val last = lastPicked
        var lastMatch: AffixItem? = null
        // key 每条只拼一次：以前 misses 查表和跟 last 比各拼一次，一轮下来翻倍。
        val weights = IntArray(items.size) { i ->
            val a = items[i]
            if (onlyKind != null && a.kind != onlyKind) {
                0
            } else {
                lastMatch = a
                val key = affixKey(a)
                val base = a.weight.coerceAtLeast(1).toDouble()
                val misses = (affixMisses[key] ?: 0).coerceAtLeast(0)
                var factor = if (misses >= AFFIX_STARVE_THRESHOLD) {
                    1.0 + (misses - AFFIX_STARVE_THRESHOLD + 1) * 2.0
                } else {
                    1.0 + misses * 0.25
                }
                if (last != null && key == last) factor *= 1.0 - penalty
                (base * factor).toInt().coerceAtLeast(1)
            }
        }
        val total = weights.sum()
        if (total <= 0) return null
        var r = Random.nextInt(total)
        for (i in items.indices) {
            r -= weights[i]
            if (r < 0) return items[i]
        }
        // 循环里必然返回（r 从 [0,total) 减完必然变负），这里是兜底：类型过滤时要退回**匹配**
        // 的最后一条，跟以前过滤完取 `.last()` 的口径一致。
        return lastMatch
    }

    /**
     * 真正发送用的抽取：挑中即记账（清零、其余 +1）。[pickWeighted] 本身不改计数，好让预览可反复调用。
     *
     * [onlyKind] 的语义跟 [pickWeighted] 一致：不在候选集里的条目计数**不动**——以前是先 filter
     * 出候选集再记账，被滤掉的那些自然碰不到。
     */
    @Synchronized
    fun pickAffix(
        items: List<AffixItem>,
        repeatPenaltyPercent: Double,
        onlyKind: AffixKind? = null,
    ): AffixItem? {
        val picked = pickWeighted(items, repeatPenaltyPercent, onlyKind) ?: return null
        val key = affixKey(picked)
        // 只给抽中的那一条建 key，其余直接拿它跟 picked 比 kind/text，省掉每条一次字符串拼接。
        // 走下标而不是 `for (a in items)`，后者每轮分配一个迭代器。
        for (i in items.indices) {
            val a = items[i]
            if (onlyKind != null && a.kind != onlyKind) continue
            if (a === picked || (a.kind == picked.kind && a.text == picked.text)) {
                affixMisses[key] = 0
            } else {
                val k = affixKey(a)
                affixMisses[k] = (affixMisses[k] ?: 0) + 1
            }
        }
        lastPicked = key
        return picked
    }

    /** 丢弃已从配置里删掉的 affix 的计数。只在拿到完整列表时调用。 */
    private fun pruneAffixMisses(items: List<AffixItem>) {
        if (affixMisses.isEmpty()) return
        affixMisses.keys.retainAll(items.mapTo(mutableSetOf()) { affixKey(it) })
    }

    /** 配置里增删 affix 后调用，避免旧计数影响新配置。 */
    fun resetAffixStreaks() {
        affixMisses.clear()
        lastPicked = null
    }

    /**
     * 判定「这句话里没有能承载口癖的正文」。链接和密钥保护的是那一段而非整句：先把受保护的片段挖掉，
     * 剩下还有字母或汉字就正常处理（`transformSentence` 里另有一道「口癖不许贴在受保护片段上」），什么
     * 都不剩才整句跳过。两个开关都关时保持原样，不凭这条多拦句子。
     */
    fun isIgnorable(sentence: String, cfg: KuchiguseConfig): Boolean {
        if (sentence.isBlank()) return true
        if (cfg.ignoreEmoji && isEmojiOnly(sentence)) return true
        if (!cfg.ignoreUrl && !cfg.ignoreRandomLike) return false
        var rest = sentence
        var masked = false
        // 遮罩惰性：扫到第一个匹配才建串，一个都没匹配上就返回同一引用，调用方靠 `!==` 判定。
        if (cfg.ignoreUrl) {
            val next = maskUrls(rest)
            if (next !== rest) {
                rest = next
                masked = true
            }
        }
        if (cfg.ignoreRandomLike) {
            val next = maskSecretTokens(rest)
            if (next !== rest) {
                rest = next
                masked = true
            }
        }
        // 什么都没保护到就照旧处理，别拿「必须有正文」这条凭空虚拦用户句子
        // （比如关掉密钥过滤后的一串手机号，以前是照改的）。
        if (!masked) return false
        return !hasProse(rest)
    }

    /**
     * 这段文本**可能**含链接吗？用来在跑 [URL_REGEX] 之前先挡掉绝大多数扫描。
     *
     * [URL_REGEX] 那个正则没有可用的字面前缀，每条分支都要逐位置试；而绝大多数聊天内容
     * （「今天天气不错啊」）压根没有任何链接，扫完什么也没得到，纯白花时间。
     *
     * 判据是**单方向的**：没有 `:` 也没有 `.` 时**一定**不含链接 —— 逐条看正则的分支，
     * `https?://` / `ftp://` 要 `:`，裸域名和 IP 要 `.`，没有例外。反过来不成立：
     * 有 `.` 未必是链接（「e.g.」「版本1.2」都不是），所以这里只当放行闸，不当判定。
     *
     * 实测（干净中文 chunk）：正则整趟 1.43 µs，这个闸 0.10 µs。
     */
    private fun mayContainUrl(s: String): Boolean =
        s.indexOf(':') >= 0 || s.indexOf('.') >= 0

    /**
     * URL 片段替换成空格；**一个都没匹配上就返回原串本身**（引用相同），调用方据此判断。语义同
     * `URL_REGEX.replace(s, " ")`，区别只在扫几趟。
     */
    private fun maskUrls(s: String): String {
        if (!mayContainUrl(s)) return s
        var sb: StringBuilder? = null
        var copied = 0
        for (m in URL_REGEX.findAll(s)) {
            if (sb == null) sb = StringBuilder(s.length)
            sb.append(s, copied, m.range.first)
            sb.append(' ')
            copied = m.range.last + 1
        }
        sb ?: return s
        sb.append(s, copied, s.length)
        return sb.toString()
    }

    /** 挖掉所有受保护的 token，只留其余；一个都没挖到就返回原串本身。与 [maskUrls] 同理。 */
    private fun maskSecretTokens(s: String): String {
        var sb: StringBuilder? = null
        var copied = 0
        var i = 0
        while (i < s.length) {
            if (isTokenChar(s[i])) {
                var j = i
                while (j < s.length && isTokenChar(s[j])) j++
                // token 内部没有空白（isTokenChar 不含空白），substring 出来就是
                // isRandomLike 内部 trim 后拿到的那个 t，长度判断正好一致。
                if (j - i >= 8 && isRandomLike(s.substring(i, j))) {
                    if (sb == null) sb = StringBuilder(s.length)
                    sb.append(s, copied, i)
                    sb.append(' ')
                    copied = j
                }
                i = j
            } else {
                i++
            }
        }
        sb ?: return s
        sb.append(s, copied, s.length)
        return sb.toString()
    }

    private fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    /** 还有没有能承载口癖的正文：至少要有一个字母或一个汉字，纯数字和标点不算。 */
    private fun hasProse(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i]
            if (c in 'a'..'z' || c in 'A'..'Z') return true
            if (isCjkCp(s.codePointAt(i))) return true
        }
        return false
    }

    /**
     * 口癖要塞进去的位置是不是正好是受保护的片段。句末正好是链接、句首正好是密钥的话，贴上去就等于
     * 把口癖粘在密钥/链接上，这种情况宁可不加。
     */
    private fun endsProtected(text: String, cfg: KuchiguseConfig): Boolean {
        if (text.isEmpty()) return false
        if (cfg.ignoreUrl && mayContainUrl(text)) {
            for (m in URL_REGEX.findAll(text)) {
                if (m.range.last == text.length - 1) return true
            }
        }
        if (cfg.ignoreRandomLike) {
            val tail = trailingToken(text)
            if (tail.length >= 8 && isRandomLike(tail)) return true
        }
        return false
    }

    private fun startsProtected(text: String, cfg: KuchiguseConfig): Boolean {
        if (text.isEmpty()) return false
        if (cfg.ignoreUrl && mayContainUrl(text)) {
            // 只问「有没有一段从 0 开始」，那就看**第一个**匹配就够：匹配从左往右给且互不
            // 重叠，首个不贴着 0 的话后面更不可能贴着 0。
            val first = URL_REGEX.find(text)
            if (first != null && first.range.first == 0) return true
        }
        if (cfg.ignoreRandomLike) {
            val head = leadingToken(text)
            if (head.length >= 8 && isRandomLike(head)) return true
        }
        return false
    }

    private fun leadingToken(s: String): String {
        if (s.isEmpty() || !isTokenChar(s[0])) return ""
        var j = 0
        while (j < s.length && isTokenChar(s[j])) j++
        return s.substring(0, j)
    }

    private fun trailingToken(s: String): String {
        if (s.isEmpty() || !isTokenChar(s[s.length - 1])) return ""
        var i = s.length - 1
        while (i >= 0 && isTokenChar(s[i])) i--
        return s.substring(i + 1)
    }

    /**
     * 判定「这句里带着密钥/口令」，这种句子不能加口癖：往 API key 后面接一个「喵」，那条 key 当场就
     * 废了。门槛是**长度 >= 8**（口令的通用下限），判据作用在按非字母数字切出的 token 上，命中任一
     * 条即整句跳过——混在一句中文里的密钥同样不能碰：
     * - 已知前缀：`sk-`、`ghp_`、`github_pat_`、`xoxb-`、`AKIA`、`AIza`、`eyJ`、`password=`、`token=`
     * - 十六进制：>= 8 位、全在 `[0-9a-f]`、且数字和字母都有（否则 `facade` 被误杀）
     * - 字母/数字类别切换 >= 4 次：`deadbeefcafe1234`、base64 片段。真人写的词内部几乎不切换
     * - 字母 >= 3 且无元音：`xkcd7f9zq`（元音表里有 y，见 [VOWELS]）
     * - 纯数字 >= 8 位：手机号、卡号、IMEI
     * - >= 2 个符号且数字字母都有：`P@ssw0rd!`
     * - 同一字符连续 >= 6，或键盘同一行连续 >= 5：`aaaaaaaa`、`asdfghjkl`
     * 已知会漏 `correcthorsebatterystaple` 这类纯小写口令（要判它得靠词典或真算熵）。宁可放过也不
     * 误伤正常句子——误伤的代价是用户真发的话没加口癖。
     */
    fun isRandomLike(sentence: String): Boolean {
        val t = sentence.trim()
        if (t.length < 8) return false
        // 已知前缀直接在「去掉空白的整句」上找：`_` `-` 是密钥里的分隔符，按 token 切会把 `ghp_xxx`
        // 拆成两段、前缀永远匹配不上。用 contains 而非 startsWith，是让 `password=xxx` 出现在句中
        // 也能命中；`token is expensive` 不带等号，不会命中。
        val compact = buildString(t.length) {
            for (c in t) if (!c.isWhitespace()) append(c.lowercaseChar())
        }
        for (pfx in SECRET_PREFIXES) if (compact.contains(pfx)) return true
        // 纯非中文、无空格、带真·符号、又有数字又有字母 —— `P@ssw0rd!x` 这种。符号表特意
        // 不含 `.` / `-` / `/`，否则 `Python3.11`、`readme.txt`、`application/json` 全被误杀。
        if (!hasCjk(t) && !hasWhitespace(t) && hasLetter(t) && hasDigit(t) &&
            t.any { it in PASSWORD_SYMBOLS }
        ) return true
        for (token in alnumTokens(t)) {
            if (token.length < 8) continue
            if (hasSecretPrefix(token)) return true
            if (isHexBlob(token)) return true
            if (classTransitions(token) >= 4) return true
            var letters = 0
            var digits = 0
            var symbols = 0
            var hasVowel = false
            for (c in token) {
                when {
                    c in 'a'..'z' || c in 'A'..'Z' -> {
                        letters++
                        if (VOWELS.indexOf(c.lowercaseChar()) >= 0) hasVowel = true
                    }
                    c in '0'..'9' -> digits++
                    else -> symbols++
                }
            }
            if (letters >= 3 && !hasVowel) return true
            if (digits == token.length) return true
            if (symbols >= 2 && digits > 0 && letters > 0) return true
        }
        if (longestRunOfSame(t) >= 6) return true
        if (longestKeyboardRowRun(t) >= 5) return true
        return false
    }

    // 元音表。**`y` 算元音**：英语里没有 aeiou 的真词基本都靠 y 撑着（rhythm / crypt / myth）。
    private const val VOWELS = "aeiouy"

    /** 已知密钥/令牌前缀。命中即判定，和长度无关（调用方已经保证 >= 8）。 */
    private val SECRET_PREFIXES = listOf(
        "sk-", "sk_", "sk_live_", "pk_live_", "rk_live_", "whsec_",
        "ghp_", "gho_", "ghu_", "ghs_", "github_pat_", "glpat-",
        "xoxb-", "xoxp-", "xoxa-", "xapp-",
        "akia", "asia", "aiza", "ya29.", "eyj", "bearer ",
        "password=", "passwd=", "pwd=", "secret=", "token=", "apikey=",
        "api_key=", "access_token=", "refresh_token=", "authorization:",
    )

    private val KEYBOARD_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890")

    private fun hasSecretPrefix(token: String): Boolean {
        val lower = token.lowercase()
        for (p in SECRET_PREFIXES) if (lower.startsWith(p)) return true
        return false
    }

    // 十六进制串：全在 `[0-9a-f]` 且数字字母都有。「都有」这条是给纯字母词留活路，只要求全 hex 会把
    // `facade` 判成密钥。
    private fun isHexBlob(token: String): Boolean {
        var digits = 0
        var letters = 0
        for (c in token) {
            when {
                c in '0'..'9' -> digits++
                c in 'a'..'f' || c in 'A'..'F' -> letters++
                else -> return false
            }
        }
        return digits > 0 && letters > 0
    }

    // 口令里真会出现的符号。**故意不含** `.` `-` `/`：那三个在版本号、文件名、路径里太常见。
    private const val PASSWORD_SYMBOLS = "@!#$%^&*_+=?~"

    /** CJK 统一表意文字（含扩展 A）。 */
    private fun isCjkCp(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF

    // 按分隔符切段。段内保留 `_` 和 `-`，因为 `ghp_`、`sk-`、`xoxb-` 的前缀紧跟本体，切开就废了。
    private fun alnumTokens(s: String): List<String> {
        val out = ArrayList<String>(4)
        val sb = StringBuilder()
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') {
                sb.append(c)
            } else if (sb.isNotEmpty()) {
                out.add(sb.toString()); sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun hasCjk(s: String): Boolean {
        for (i in s.indices) if (isCjkCp(s.codePointAt(i))) return true
        return false
    }

    private fun hasWhitespace(s: String): Boolean {
        for (i in s.indices) if (s[i].isWhitespace()) return true
        return false
    }

    private fun hasLetter(s: String): Boolean = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    private fun hasDigit(s: String): Boolean = s.any { it in '0'..'9' }

    /** 字母/数字之间的切换次数：`deadbeefcafe1234` 很多次，`congratulations` 是 0 次。 */
    private fun classTransitions(token: String): Int {
        var n = 0
        var prevIsLetter = false
        for (i in token.indices) {
            val isLetter = token[i] in 'a'..'z' || token[i] in 'A'..'Z'
            if (i > 0 && isLetter != prevIsLetter) n++
            prevIsLetter = isLetter
        }
        return n
    }

    private fun longestRunOfSame(s: String): Int {
        var best = 0
        var run = 0
        var prev = ' '
        for (c in s) {
            run = if (c == prev) run + 1 else 1
            prev = c
            if (run > best) best = run
        }
        return best
    }

    // 键盘同一行上的连续按键，字母键序按 PC QWERTY。大小写都要看，所以先转小写。
    private fun longestKeyboardRowRun(s: String): Int {
        var best = 0
        val lower = s.lowercase()
        var i = 0
        while (i < lower.length) {
            for (row in KEYBOARD_ROWS) {
                if (row.indexOf(lower[i]) < 0) continue
                var run = 1
                var j = i + 1
                while (j < lower.length) {
                    val a = row.indexOf(lower[j - 1])
                    val b = row.indexOf(lower[j])
                    if (b < 0 || kotlin.math.abs(b - a) != 1) break
                    run++
                    j++
                }
                if (run > best) best = run
                break
            }
            i++
        }
        return best
    }

    /**
     * 除 emoji、标点、空白之外还有没有真文字。只要一个是否的答案，所以直接扫出布尔值、遇第一个正
     * 文字符就早退——绕道拼字符串再 `isBlank()` 会按字符数分配。
     */
    fun isEmojiOnly(strippedishText: String): Boolean = !hasProseAfterEmoji(strippedishText)

    private fun hasProseAfterEmoji(text: String): Boolean {
        val n0 = text.length
        var i = 0
        while (i < n0) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (isEmojiCodePoint(cp)) {
                i += len
                while (i < n0) {
                    val n = text.codePointAt(i)
                    if (n == 0xFE0F || n == 0xFE0E || n == 0x200D || n in 0x1F3FB..0x1F3FF) {
                        i += Character.charCount(n)
                    } else break
                }
                continue
            }
            if (isPunctPlaceholderCp(cp) || Character.isWhitespace(cp)) { i += len; continue }
            return true
        }
        return false
    }

    private fun isPunctPlaceholderCp(cp: Int): Boolean {
        return cp in 0x3000..0x303F || cp in 0xFF01..0xFF65 ||
            cp == '。'.code || cp == '，'.code || cp == '！'.code || cp == '？'.code || cp == '…'.code ||
            cp == '\u2026'.code || cp == '\uFF0E'.code || cp == '.'.code
    }

    data class ChunkPreview(
        val text: String,
        val probability: Double,
        val forced: Boolean,
        val ignored: Boolean,
        val hit: Boolean,
        val result: String,
        /**
         * 这一条预览实际用的是哪个口癖。必须由引擎如实报上来，不能让界面自己再抽一次猜位置：逐句
         * 模式下每句各抽一次，界面那次独立的抽签对不上就会出现「加上了却没染色」。
         */
        val affix: AffixItem? = null,
    )

    /**
     * 设置页实时检测器用。必须与 [process] 逐字一致——两条是独立实现，漂移一次就会被检测器指到错误
     * 位置。改 [process] 侧的排版逻辑必须同步改这里。
     */
    fun previewChunks(text: String, cfg: KuchiguseConfig): List<ChunkPreview> {
        if (!cfg.enabled) {
            return splitSentences(text).map { ChunkPreview(it, 0.0, false, false, false, it) }
        }
        // 补句号是整条消息级的一步，先做完再切句，预览才不会和真正发出去的内容对不上。process() 跑
        // 在 trim 过的文本上，预览必须从同一份文本出发，否则行数、空行和首尾空白会对不上。
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val periodText = if (cfg.botChinesePeriod) addChinesePeriod(trimmed, cfg) else trimmed
        val rawChunks = splitSentences(periodText)
        // 屏蔽词是整条消息的闸门：process() 命中就直接放弃，预览也得如实显示不处理，
        // 不然会给一条实际根本不会改动的内容上色。
        if (hitsBlockKeyword(trimmed, cfg)) {
            return rawChunks.map { ChunkPreview(it, 0.0, false, true, false, it) }
        }
        val affixList = cfg.affixes.takeIf { it.isNotEmpty() }
            ?: return rawChunks.map { ChunkPreview(it, 0.0, false, false, false, it) }
        // 整条模式整段共用一个口癖，这里先抽好；逐句模式留到每句各抽。
        val sharedAffix = if (cfg.scope == Scope.WHOLE_MESSAGE) {
            pickWeighted(affixList, cfg.affixRepeatPenalty)
                ?.takeIf { it.text.isNotEmpty() }
                ?: return rawChunks.map { ChunkPreview(it, 0.0, false, false, false, it) }
        } else {
            null
        }
        val classified = classifyChunks(rawChunks, cfg)
        val hasForcedCouplet = classified.any { it.forced }
        val poolProb = poolProbability(cfg, classified, trimmed, hasForcedCouplet)
        // pickWeighted 只读计数、不记账，反复点测试不会污染下一次真实发送。
        fun pickPreview(hit: Boolean): AffixItem? = when {
            !hit -> null
            cfg.scope == Scope.PER_SENTENCE ->
                pickWeighted(affixList, cfg.affixRepeatPenalty)?.takeIf { it.text.isNotEmpty() }
            else -> sharedAffix
        }
        // 句尾波浪号挂在最后一句能贴口癖的那句后面，规则和 applyPerSentence 完全一致 ——
        // 预览必须和实际发出去的一模一样，否则设置页那个实时检测器会指着错误的位置。
        var tildeAt = -1
        for (i in classified.indices.reversed()) {
            if (classified[i].operable) { tildeAt = i; break }
        }
        val tilde = tildeAt >= 0 && tailTildeEnabled(cfg)

        if ((poolProb > 0 && Random.nextDouble() >= poolProb) && !hasForcedCouplet) {
            return classified.mapIndexed { i, plan ->
                val forced = plan.forced
                val hit = forced
                val affix = pickPreview(hit)
                val result = rewriteSentence(plan.raw, affix, cfg, tailTilde = tilde && i == tildeAt)
                ChunkPreview(plan.raw, if (forced) 1.0 else poolProb, forced, false, hit, result, affix)
            }
        }
        return finishPlans(classified, cfg, poolProb).mapIndexed { i, plan ->
            // 这里用 plan.dots 就够，它在 finishPlans 里已经是 `poolProb × lengthProbability`。
            val prob = if (plan.forced) 1.0 else plan.dots
            val hit = shouldProcess(plan)
            val affix = pickPreview(hit)
            val result = rewriteSentence(plan.raw, affix, cfg, tailTilde = tilde && i == tildeAt)
            ChunkPreview(plan.raw, prob, plan.forced, plan.ignored, hit, result, affix)
        }
    }

    /**
     * 检测工整对仗句：按中文逗号/顿号/分号分割，至少两段，相邻两段字数相等、各段 2..12 字、中文字符
     * 占比 ≥ 66%。例：「床前明月光，疑是地上霜」（5-5）。
     */
    fun isCoupletLike(sentence: String): Boolean {
        var lengths = IntArray(8)
        var n = 0
        var total = 0
        var chinese = 0
        val n0 = sentence.length
        var segStart = 0
        var segChinese = 0
        var idx = 0
        while (idx <= n0) {
            val atEnd = idx == n0
            if (!atEnd && COUPLET_SEPARATORS.indexOf(sentence[idx]) < 0) {
                if (isCjkCp(sentence.codePointAt(idx))) segChinese++
                idx++
                continue
            }
            // 段尾标点和空白不计入字数，等价于 trim() + trimEnd(标点)
            var end = idx
            while (end > segStart && isCoupletTrimChar(sentence[end - 1])) end--
            var start = segStart
            while (start < end && sentence[start].isWhitespace()) start++
            val len = end - start
            if (len > 0) {
                if (n == lengths.size) lengths = lengths.copyOf(n * 2)
                lengths[n++] = len
                total += len
                chinese += segChinese
            }
            segStart = idx + 1
            segChinese = 0
            idx++
        }
        if (n < 2 || total == 0) return false
        if (chinese.toDouble() / total < 0.66) return false
        if (n % 2 != 0) return false
        var i = 0
        while (i < n) {
            if (lengths[i] != lengths[i + 1]) return false
            if (lengths[i] !in 2..12) return false
            i += 2
        }
        return true
    }

    /** 对仗分句的分隔符，与拆分用的分隔符集合保持一致。 */
    private const val COUPLET_SEPARATORS = ",，、；;"

    /** 段尾要剥掉的标点：句读 + 分隔符本身 + 空白。 */
    private fun isCoupletTrimChar(c: Char): Boolean =
        c.isWhitespace() || COUPLET_SEPARATORS.indexOf(c) >= 0 ||
            c == '。' || c == '！' || c == '？' || c == '…'
}

/**
 * 按 [key] 缓存一次抽签结果，**null 也算一次结果**：只要这个 key 抽过（哪怕抽出来是 null）
 * 就不再抽第二次，返回缓存里的那个值。
 *
 * 不能用 `getOrPut`：它只在现有值非 null 时才跳过，缓存住一个 null 的话每回都会重新调
 * [draw]，「整条消息同类型只抽一次底句」就落空，而且每次重抽还会推进随机数。
 */
internal fun <K, V> MutableMap<K, V?>.cachedDraw(key: K, draw: () -> V?): V? {
    if (containsKey(key)) return get(key)
    return draw().also { put(key, it) }
}
