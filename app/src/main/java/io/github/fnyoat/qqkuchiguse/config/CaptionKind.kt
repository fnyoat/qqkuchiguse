package io.github.fnyoat.qqkuchiguse.config

/**
 * 一类图片/表情。
 *
 * 分成四份是因为它们的默认底句本来就不一样：`[图片]`、`[动画表情]`、`[表情]` 各是 QQ 资源表里
 * 独立的条目，用户想换底句或者干脆关掉某一种，是各自独立的事，不该捆在一张表上。
 */
enum class CaptionKind {
    /** 相册图片、截图、图文混排。`PicElement` 且 `picSubType` 为 0。 */
    PICTURE,

    /** 动图表情。`picSubType` 为 1。 */
    ANIMATED_EMOJI,

    /** 表情搜索、表情消息、表情推荐。`picSubType` 为 2/4/7。 */
    EMOJI,

    /** 表情泡泡。`FaceBubbleElement`，跟上面三种不是同一个元素。 */
    FACE_BUBBLE,
    ;

    companion object {
        /**
         * 图片和表情都是 `PicElement`，只能靠 `picSubType` 分。
         *
         * `picSubType` 是 QQ 自己的子类型：0 是纯图片和图文混排，1 是动画表情，2/4/7 分别是
         * 表情搜索、表情消息、表情推荐。所以 0 当图片、1 当动画表情、其余当表情。
         *
         * 1 必须单独认：底句是 `[动画表情]` 而不是 `[表情]`，认错了就把动图写成普通表情。
         * 没见过的子类型当普通表情；字段整个读不到时退回图片，图片是绝大多数情况。
         */
        fun forPicSubType(picSubType: Int?): CaptionKind = when (picSubType) {
            null, 0 -> PICTURE
            1 -> ANIMATED_EMOJI
            else -> EMOJI
        }
    }
}

/**
 * 一类图片/表情拿口癖的方式。一个下拉四档，不要两个开关拼出四档 ——
 * 开关的组合里有一半是说不清的（关掉随机底句但关掉这一类跟开着没区别）。
 */
enum class CaptionMode {
    /** 这一类一个字都不动。 */
    OFF,

    /** 用主表那一份口癖。也就是「这一类不单独设置」。 */
    MAIN_AFFIX,

    /** 用公共口癖表 [KuchiguseConfig.imageDescAffixes]，四类共用同一张。 */
    AFFIX,

    /**
     * 底句换成随机文案，整条消息只抽一次，每句都用抽中的那句。
     *
     * 「整条」是跟文字消息那边的「口癖抽签范围」一个口径：整条只抽一次，
     * 而不是每句各抽一次 —— 一次会话里看的是同一条消息，逐句抽会抽出自相矛盾的几句底句。
     */
    RANDOM_WHOLE_MSG,
    ;

    companion object {
        fun fromJson(name: String?): CaptionMode? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 一类图片/表情自己的配置。
 *
 * 口癖表只有一张公共的 [KuchiguseConfig.imageDescAffixes]，四类共用 —— 之前是每类一张，
 * 但「图片要用这套、表情要用那套」这种需求没出现过，四张表摆在一起只是让用户四条都改一遍。
 * 所以这里只留各类型的底句表（[bases]），口癖去 [CaptionMode] 里选主表还是公共表。
 */
data class CaptionKindConfig(
    /** 这一类拿口癖的方式。 */
    val mode: CaptionMode = CaptionMode.MAIN_AFFIX,
    /** [CaptionMode.RANDOM_WHOLE_MSG] 用的底句表。留空就是不出随机底句，底句仍旧用 QQ 那句。 */
    val bases: List<String> = emptyList(),
) {
    companion object {
        /**
         * 旧配置里这一类是 [enabled] + [randomBase] 两个开关，搬到 [mode] 上。
         *
         * 迁移只做照搬，不替用户挑：关掉过就是 [CaptionMode.OFF]，开着的按有没有开随机底句分
         * [CaptionMode.RANDOM_WHOLE_MSG] 和剩下那档。剩下那档到底是「用公共表」还是「跟主表」，
         * 看这一类自己有没有填过东西 —— 填过就是想把这一类的口癖跟正文分开。
         *
         * [hadOwnList] 旧版指的是这一类自己的口癖表；现在口癖表是公共的，那个意图就落到
         * [CaptionMode.AFFIX] 上，跟「这一类填过底句表」是同一件事。
         */
        fun migrate(enabled: Boolean, randomBase: Boolean, hadOwnList: Boolean): CaptionMode = when {
            !enabled -> CaptionMode.OFF
            randomBase -> CaptionMode.RANDOM_WHOLE_MSG
            hadOwnList -> CaptionMode.AFFIX
            else -> CaptionMode.MAIN_AFFIX
        }
    }
}
