package io.github.fnyoat.qqkuchiguse.hook

import android.content.Context
import io.github.fnyoat.qqkuchiguse.config.CaptionKind

/**
 * QQ 自己给图片 / 表情准备的那句默认描述。
 *
 * 这句字**不在消息元素上**。设备上实测过：发送时 `picElement.summary` 是空的，
 * `textElement` 是 null，`PicElement` 上也没有任何无参方法能算出 `[图片]`。QQ 是在渲染
 * 聊天列表那一刻才去资源表里取出来显示的，所以「读原描述再拼」这条路根本读不到东西 ——
 * 之前那版一写就把底句丢了，正是因为它。
 *
 * 于是改成跟 QQ 用同一份数据：它的 `resources.arsc` 里确实有一整套消息占位符
 * （`[图片]` `[表情]` `[动画表情]` `[语音]` `[视频]` `[闪照]` `[秀图]` `[文件]`），
 * 用 `getIdentifier` 按名字取就是 QQ 自己那句字，语言变了它也跟着变。
 *
 * **表是从 QQ 的 arsc 里生成的，不是手抄的。** 资源名是构建期混淆出来的短名
 * （`[图片]` → `aek`，`[表情]` → `wh9`，`[动画表情]` → `iml`），QQ 一升级就可能换，
 * 所以：
 *
 * ```
 * python3 tools/arsc.py report qq/resources.arsc '[图片]' '[表情]' '[动画表情]' \
 *     > qq/placeholders.json
 * ```
 *
 * 在 `fnyoat/fnyoat_codespace` 里跑（arsc 有 9 MB，别在手机上解）。生成结果见
 * 同仓库 `qq/placeholders.json`，改完把 [FALLBACKS] 和 [NAMES] 一起更新。
 *
 * 名字对不上时（QQ 换了混淆规则）退回 [FALLBACKS] 里那个字面量 —— 那是同一张表里
 * 抽出来的原文，所以退的也只是「不跟随语言」，不是「编一句假话」。
 */
/**
 * 底句取值。优先用 QQ 资源表里的活资源，解析不到才用字面量兜底。
 *
 * 同一个 kind 的结果在进程内缓存一次：`getIdentifier` 要走 `AssetManager`，
 * 每发一张图都问一遍不划算，而这个值一个进程里不会变。
 */
object QqPlaceholder {

    private const val PKG = "com.tencent.mobileqq"

    /** kind → 资源名。生成自 QQ 的 arsc，见类注释里的命令。 */
    private val NAMES = mapOf(
        CaptionKind.PICTURE to "aek",
        CaptionKind.EMOJI to "wh9",
        CaptionKind.ANIMATED_EMOJI to "iml",
        // 表情泡泡在这个 QQ 版本里**没有**自己的占位符：arsc 的字符串池里搜不到「锅」，
        // 所以复用 `[表情]` 那条，而不是编一句 `[平底锅]` 出来。它的底句本来就主要来自
        // 元素自己的 content，这里只在 content 为空时兜底。
        CaptionKind.FACE_BUBBLE to "wh9",
    )

    /** kind → 资源名对不上时的兜底字面量，同样是 arsc 里的原文。 */
    private val FALLBACKS = mapOf(
        CaptionKind.PICTURE to "[图片]",
        CaptionKind.EMOJI to "[表情]",
        CaptionKind.ANIMATED_EMOJI to "[动画表情]",
        CaptionKind.FACE_BUBBLE to "[表情]",
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<CaptionKind, String>()

    /**
     * 这类元素在聊天列表里原本显示的那句话。
     *
     * [ctx] 拿不到（宿主进程还没起完）就只返回字面量，不抛。
     */
    fun defaultDesc(kind: CaptionKind, ctx: Context?): String {
        cache[kind]?.let { return it }
        val resolved = resolve(kind, ctx) ?: (FALLBACKS[kind] ?: "")
        if (resolved.isNotEmpty()) cache[kind] = resolved
        return resolved
    }

    private fun resolve(kind: CaptionKind, ctx: Context?): String? {
        val name = NAMES[kind] ?: return null
        if (ctx == null) return null
        return runCatching {
            val id = ctx.resources.getIdentifier(name, "string", PKG)
            if (id == 0) return@runCatching null
            ctx.getString(id).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 清缓存。换语言或重载资源后调，否则第一次取到的值会一直留着。 */
    fun clearCache() = cache.clear()
}
