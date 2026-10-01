package io.github.fnyoat.qqkuchiguse

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge
import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig
import io.github.fnyoat.qqkuchiguse.config.ListMode

/**
 * 名单页：四份名单各自一页，一次只看一份。
 *
 * 单独一个页面而不是设置页里的一段，因为四份名单摊在设置页里会把那一页拉得老长，
 * 而设置页本来是给开关和数字用的。
 *
 * 只有当前生效的那一份在这里显示条目；没生效的那三份只说一句话，不铺条目。
 * 想整理没生效的那份，先在上面把生效名单切成它，切过去它就有条目了。
 *
 * 改动一律立刻写盘，不设「保存」按钮：悬浮面板在 QQ 里改的也是同一份文件，
 * 攒着不写只会让两边互相覆盖。写盘走 [ConfigBridge.writeFromApp]，它会顺带刷新
 * 宿主进程里的缓存，所以 QQ 里的面板立刻就能看到这里的删除。
 */
class SessionListsActivity : Activity() {

    private data class ListId(val isGroup: Boolean, val mode: ListMode)

    private val allLists = listOf(
        ListId(false, ListMode.WHITELIST),
        ListId(false, ListMode.BLACKLIST),
        ListId(true, ListMode.WHITELIST),
        ListId(true, ListMode.BLACKLIST),
    )

    private lateinit var nav: LinearLayout
    private lateinit var body: LinearLayout
    private var current: KuchiguseConfig = KuchiguseConfig()
    // 落在单聊生效的那一份上。和 allLists 一样不能用它当初值：属性按声明顺序
    // 初始化，引用下面才声明的 allLists 只会拿到 null。
    private var selected: ListId = ListId(false, ListMode.WHITELIST)

    override fun onCreate(savedInstanceState: Bundle?) {
        // QQ 进程里 ActProxy 换过 Intent，主题必须在任何 getString / setContentView
        // 之前换成我们自己的，否则界面会退回 QQ 的老 Android 观感。
        setTheme(R.style.Theme_Kuchiguse)
        super.onCreate(savedInstanceState)
        title = getString(R.string.session_lists_title)
        current = (ConfigBridge.load(ConfigBridge.appFile(this)) as? ConfigBridge.Load.Ok)?.cfg
            ?: KuchiguseConfig()
        // 默认停在单聊生效的那一份上。
        selected = ListId(false, current.singleListMode)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 面板那边随时可能改过名单，回来时重读一遍，别拿旧快照盖回去。
        current = (ConfigBridge.load(ConfigBridge.appFile(this)) as? ConfigBridge.Load.Ok)?.cfg
            ?: KuchiguseConfig()
        if (this::nav.isInitialized) refresh()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = getString(R.string.lists_section_hint)
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 13f
        }, lp)

        nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, lp)

        refresh()
        return ScrollView(this).apply { addView(root) }
    }

    /** 标签栏和下面这一页一起重画。标签要跟着高亮，所以整条都过一遍。 */
    private fun refresh() {
        nav.removeAllViews()
        allLists.forEach { id ->
            nav.addView(tab(id), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        body.removeAllViews()
        val id = selected
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val active = id.mode == current.listModeFor(id.isGroup)
        val shown = current.sessionsIn(id.isGroup, id.mode)

        // 未生效的那份也列条目：两份名单各自独立，都得能看、能删、能清空。早先这里只铺
        // 一句「当前未生效」就 return，条目一个都不渲染，那份名单成了只写不可见的死角。
        body.addView(TextView(this).apply {
            text = if (active) {
                getString(R.string.session_list_title_active, listNameOf(id), shown.size)
            } else {
                getString(R.string.session_list_title_inactive, listNameOf(id), shown.size)
            }
            setTextColor(resources.getColor(if (active) R.color.ok else R.color.text_secondary, theme))
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, lp)
        // 不生效要讲清楚「这些条目现在不起作用」，否则用户会以为规则丢了。
        if (!active) {
            body.addView(caption(getString(R.string.session_list_off_notice)), lp)
        }
        // 空名单不给「清空」：清空一份本来就空的名单没有意义，白占一个按钮。
        if (shown.isEmpty()) {
            body.addView(caption(getString(R.string.session_list_empty)), lp)
        } else {
            body.addView(Button(this).apply {
                text = getString(R.string.session_list_clear_all)
                textSize = 14f
                setTextColor(resources.getColor(R.color.danger, theme))
                background = null
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
                setOnClickListener {
                    AlertDialog.Builder(this@SessionListsActivity)
                        .setMessage(getString(R.string.session_list_clear_all_confirm, shown.size))
                        .setPositiveButton(R.string.ok) { _, _ -> clearList(id) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }, lp)
        }
        shown.forEach { key ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = getString(if (id.isGroup) R.string.group_chat else R.string.single_chat, key)
                setTextColor(resources.getColor(
                    if (active) R.color.text_primary else R.color.text_secondary,
                    theme,
                ))
                textSize = 14f
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = getString(R.string.delete)
                textSize = 12f
                setTextColor(resources.getColor(R.color.danger, theme))
                background = null
                setOnClickListener { removeKey(id, key) }
            })
            body.addView(row, lp)
        }
    }

    private fun tab(id: ListId): TextView = TextView(this).apply {
        text = getString(
            when (id) {
                ListId(false, ListMode.WHITELIST) -> R.string.tab_single_whitelist
                ListId(false, ListMode.BLACKLIST) -> R.string.tab_single_blacklist
                ListId(true, ListMode.WHITELIST) -> R.string.tab_group_whitelist
                else -> R.string.tab_group_blacklist
            })
        textSize = 13f
        gravity = Gravity.CENTER
        val picked = id == selected
        // 当前生效的那份用绿色 —— 同类里白/黑只有一份在生效，那份才决定判定结果。
        val effective = id.mode == current.listModeFor(id.isGroup)
        // 选中态必须还能一眼看出来，不能被上面的绿色盖掉：选中的一律加粗 + 主色描边，
        // 绿色只表示「这份在生效」。两个维度分开，否则四份名单里生效的那份永远绿，
        // 切换标签时看上去什么都没变。
        setTextColor(resources.getColor(
            when {
                effective -> R.color.ok
                picked -> R.color.accent
                else -> R.color.text_secondary
            },
            theme,
        ))
        setTypeface(if (picked || effective) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(resources.getColor(
                when {
                    picked -> R.color.accent_wash
                    effective -> R.color.ok_wash
                    else -> R.color.card
                },
                theme,
            ))
            if (picked) {
                setStroke(dp(2), resources.getColor(R.color.accent, theme))
            } else if (effective) {
                setStroke(dp(1), resources.getColor(R.color.ok, theme))
            }
        }
        // 带上标签文案本身：这四条都是 %1$s 开头，不传参的话读屏会念出字面的 "%1$s"。
        contentDescription = getString(
            when {
                picked && effective -> R.string.tab_state_picked_effective
                picked -> R.string.tab_state_picked
                effective -> R.string.tab_state_effective
                else -> R.string.tab_state_plain
            },
            text.toString(),
        )
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener {
            if (id == selected) return@setOnClickListener
            selected = id
            refresh()
        }
    }

    /** 移出名单 = 不再特殊规定；不是「反过来塞进同一类的另一份名单」。 */
    private fun removeKey(id: ListId, key: String) {
        val next = current.withoutListedIn(id.isGroup, id.mode, key)
        if (!persist(next)) return
        current = next
        refresh()
    }

    /** 只清这一页这一份：同一类的另一份和另一类都原样留着。 */
    private fun clearList(id: ListId) {
        val next = current.withClearedList(id.isGroup, id.mode)
        if (!persist(next)) return
        current = next
        refresh()
    }

    private fun persist(next: KuchiguseConfig): Boolean {
        if (ConfigBridge.writeFromApp(this, next)) return true
        Toast.makeText(this, R.string.save_failed_toast, Toast.LENGTH_LONG).show()
        return false
    }

    /** 「单聊白名单」这样的全名，标题里要用。 */
    private fun listNameOf(id: ListId): String = getString(
        if (id.isGroup) R.string.scope_group_short else R.string.scope_single_short) +
        getString(if (id.mode == ListMode.WHITELIST) R.string.list_name_whitelist else R.string.list_name_blacklist)

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(resources.getColor(R.color.text_secondary, theme))
        textSize = 13f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
