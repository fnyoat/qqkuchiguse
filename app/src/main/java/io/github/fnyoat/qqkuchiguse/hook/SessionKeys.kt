package io.github.fnyoat.qqkuchiguse.hook

import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig

/**
 * 判定前把四份名单里「号码化之前写进去的 `u_` 条目」换成现在认的 key。
 *
 * v85 起 key 是 QQ 号，老名单里存的还是 `u_...`，`key in 名单` 永远不成立，
 * 白名单看着还在却一条都不生效。换不掉的原样留着：那时 key 本身也是 uid，照样对得上。
 * 跟着 [UinLookup.generation] 重算，因为接口就绪得晚，先前换不出来的会补上。
 */
object SessionKeys {
    private val UID_PREFIX = "u_"

    private var lastIn: KuchiguseConfig? = null
    private var lastGeneration = -1L
    private var lastOut: KuchiguseConfig? = null

    /**
     * 判定/显示用的配置。行为和传进来的完全一样，只是老条目被换成了号码。
     *
     * 这个结果只用来判定，**不直接写盘**；用户下一次从面板或名单页改动配置时，
     * 迁移后的值会跟着写回去，迁移就自然完成了。
     */
    @Synchronized
    fun effective(cfg: KuchiguseConfig): KuchiguseConfig {
        if (!hasLegacyEntries(cfg)) return cfg
        val generation = UinLookup.generation.get()
        if (cfg === lastIn && generation == lastGeneration) return lastOut!!
        val migrated = migrate(cfg)
        lastIn = cfg
        lastGeneration = generation
        lastOut = migrated
        return migrated
    }

    private fun hasLegacyEntries(cfg: KuchiguseConfig): Boolean =
        hasLegacy(cfg.singleWhitelist) || hasLegacy(cfg.singleBlacklist) ||
            hasLegacy(cfg.groupWhitelist) || hasLegacy(cfg.groupBlacklist)

    private fun hasLegacy(entries: Set<String>): Boolean = entries.any { it.startsWith(UID_PREFIX) }

    private fun migrate(cfg: KuchiguseConfig): KuchiguseConfig {
        val sw = mapEntries(cfg.singleWhitelist)
        val sb = mapEntries(cfg.singleBlacklist)
        val gw = mapEntries(cfg.groupWhitelist)
        val gb = mapEntries(cfg.groupBlacklist)
        // 一条都没换掉就还是原对象，免得给调用方一个「看着变了其实没变」的副本。
        if (sw === cfg.singleWhitelist && sb === cfg.singleBlacklist &&
            gw === cfg.groupWhitelist && gb === cfg.groupBlacklist
        ) {
            return cfg
        }
        return cfg.copy(
            singleWhitelist = sw,
            singleBlacklist = sb,
            groupWhitelist = gw,
            groupBlacklist = gb,
        )
    }

    /**
     * 换不出来的条目原样保留。
     *
     * 刻意**不**把没换掉的记成永久失败：接口可能只是还没就绪。留着它们，下次
     * [UinLookup.generation] 一变就自动重试。而此时会话 key 本身也是 uid，不会错配。
     */
    private fun mapEntries(entries: Set<String>): Set<String> {
        if (!hasLegacy(entries)) return entries
        val out = LinkedHashSet<String>(entries.size)
        var changed = false
        for (e in entries) {
            val mapped = if (e.startsWith(UID_PREFIX)) UinLookup.uinFromUid(e) ?: e else e
            if (mapped != e) changed = true
            // 换出来的是个 set：万一号码化之后老条目和现条目撞上了，自动合成一条。
            out.add(mapped)
        }
        return if (changed) out else entries
    }
}
