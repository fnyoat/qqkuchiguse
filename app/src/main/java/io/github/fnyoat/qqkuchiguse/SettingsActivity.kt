/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/*
 * qq-kuchiguse - 给发出的 QQ 消息追加口癖（前缀/后缀）的独立 Xposed 模块设置页。
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package io.github.fnyoat.qqkuchiguse

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.util.Log
import android.view.View
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.fnyoat.qqkuchiguse.config.AffixItem
import io.github.fnyoat.qqkuchiguse.config.CaptionKind
import io.github.fnyoat.qqkuchiguse.config.CaptionKindConfig
import io.github.fnyoat.qqkuchiguse.config.CaptionMode
import io.github.fnyoat.qqkuchiguse.config.AffixKind
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge
import io.github.fnyoat.qqkuchiguse.hook.KuchiguseCore
import io.github.fnyoat.qqkuchiguse.config.ImageDescMode
import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig
import io.github.fnyoat.qqkuchiguse.config.PanguMode
import io.github.fnyoat.qqkuchiguse.config.ListMode
import io.github.fnyoat.qqkuchiguse.config.Replacement
import io.github.fnyoat.qqkuchiguse.config.Scope
import io.github.fnyoat.qqkuchiguse.engine.KuchiguseEngine
import java.util.Locale
import io.github.fnyoat.qqkuchiguse.engine.KuchiguseEngine.ChunkPreview
import kotlin.math.roundToInt

/**
 * 独立设置页。从桌面图标启动，编辑后写进 QQ 自己的 external files dir
 * 里的配置文件（见 [ConfigBridge]）。
 *
 * 模块 App 不常驻、不轮询、不推送：用户打开才运行，写完就退出。
 * QQ 进程在**启动时**读一次配置并缓存；改动何时生效由用户在悬浮面板
 * 点「立刻重载配置」决定。
 */
class SettingsActivity : Activity() {

    private companion object {
        const val LOGTAG = "KuchiguseSettings"
    }

    private lateinit var root: LinearLayout
    private lateinit var enableSwitch: Switch
    private var masterSwitchTouched = false
    private lateinit var probabilityEdit: EditText
    private lateinit var lengthGateEdit: EditText
    private lateinit var gradientEdit: EditText
    private lateinit var scopeWhole: CheckBox
    private lateinit var ignoreUrlSwitch: Switch
    private lateinit var ignoreEmojiSwitch: Switch
    private lateinit var hideSessionNumberSwitch: Switch
    private lateinit var ignoreRandomSwitch: Switch
    private lateinit var antithesisSwitch: Switch
    private lateinit var floatSwitch: Switch
    private lateinit var blockKeywordsEdit: EditText
    private lateinit var imageDescSwitch: Switch
    private lateinit var imageDescKindBox: LinearLayout
    private val captionKindViews = mutableMapOf<CaptionKind, CaptionKindViews>()
    private lateinit var imageDescPublicBox: LinearLayout

    /** 随机底句表在设置页上只占一行，表本身在 dialog 里。 */
    private lateinit var imageDescBaseBox: LinearLayout
    private lateinit var advancedContainer: LinearLayout

    private lateinit var singleListModeGroup: RadioGroup
    private lateinit var singleListModeWhitelist: RadioButton
    private lateinit var singleListModeBlacklist: RadioButton
    private lateinit var groupListModeGroup: RadioGroup
    private lateinit var groupListModeWhitelist: RadioButton
    private lateinit var groupListModeBlacklist: RadioButton

    /** 上面标签栏点着的那一份，也就是下面这一页正在显示的名单。 */

    private lateinit var testerInput: EditText
    private lateinit var testerButton: Button
    private lateinit var lengthCurve: LengthCurveView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var testerActive = false
    private var testerOriginal: String = ""
    private lateinit var stabilityEdit: EditText
    private lateinit var stabilityMinGuaranteedEdit: EditText
    private lateinit var lengthAutoAdjustPoolSwitch: Switch
    private lateinit var floatSizeEdit: EditText
    private lateinit var floatAlphaEdit: EditText

    private lateinit var affixNote: TextView
    private lateinit var affixContainer: LinearLayout
    private lateinit var affixRepeatPenaltyEdit: EditText
    private lateinit var allowPrefixSuffixTogether: Switch
    private lateinit var panguRow: LinearLayout
    private lateinit var panguValue: TextView

    /** 弹框里三行的顺序，勾选和回填都按这个下标走。 */
    private val panguOrder = listOf(PanguMode.OFF, PanguMode.AFFIX, PanguMode.WHOLE_MESSAGE)

    /** 弹框里三行的顺序，勾选和回填都按这个下标走。 */
    private val imageDescOrder = listOf(ImageDescMode.OFF, ImageDescMode.DEDICATED, ImageDescMode.MAIN)
    private lateinit var affixSpaceLatinLatin: Switch
    private lateinit var botChinesePeriod: Switch
    private lateinit var lineEndsSentence: Switch
    private lateinit var affixTailTilde: Switch

    /** 去掉多余的 .0，整数就显示整数。 */
    private fun formatPercent(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else v.toString()
    private lateinit var replacementContainer: LinearLayout

    private var current: KuchiguseConfig = KuchiguseConfig()

/** 读盘结果；Unreadable 时界面必须明确提示「下面不是 QQ 正在用的配置」。 */
private var configLoad: ConfigBridge.Load = ConfigBridge.Load.Missing

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Intent 已被 ActProxy 换成 QQ 的 stub，ActivityThread 取到的是 stub 的
        // theme，界面会退回老 Android 观感。必须在任何 getString / setContentView
        // 之前换成我们自己的主题；此时 ActProxy 已在 callActivityOnCreate 里把模块
        // 资源挂上，所以这个 style 能被解析到。
        setTheme(R.style.Theme_Kuchiguse)
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        configLoad = ConfigBridge.load(ConfigBridge.appFile(this))
        current = (configLoad as? ConfigBridge.Load.Ok)?.cfg ?: KuchiguseConfig()
        masterSwitchTouched = false
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

        // 配置读不到就先说清楚：不然下面所有字段显示的都是默认值，用户会以为那就是生效值
        // （真发生过：界面 maxLength=5，QQ 里其实在用 10）。这页只能被 QQ 进程拉起，所以到
        // 这里读不到已经不是权限问题，只能是 QQ 自己还没把目录建出来或者被清掉了。
        if (configLoad is ConfigBridge.Load.Unreadable) {
            root.addView(configUnreadableBanner(), lp)
        }

        addSection(R.string.tester_section)
        testerInput = EditText(this).apply {
            textSize = 15f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setShowSoftInputOnFocus(true)
        }
        root.addView(testerInput, lp)
        testerButton = Button(this).apply {
            setText(R.string.tester_run)
            setOnClickListener { runTester() }
        }
        val testerLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
        root.addView(testerButton, testerLp)

        addSection(R.string.general_section)
        enableSwitch = switchRow(R.string.enable_module)
        enableSwitch.setOnCheckedChangeListener { _, _ -> masterSwitchTouched = true }

        root.addView(fieldRow(getString(R.string.probability_pool_label)), lp)
        probabilityEdit = singleLineField()
        probabilityEdit.setText(current.probabilityExpr)
        root.addView(probabilityEdit, lp)
        root.addView(caption(getString(R.string.probability_pool_hint)), lp)

        root.addView(sectionDivider(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(3) })

        root.addView(fieldRow(getString(R.string.length_prob_label)), lp)
        lengthGateEdit = singleLineField()
        lengthGateEdit.setText(current.maxLength.toString())
        root.addView(lengthGateEdit, lp)
        root.addView(caption(getString(R.string.length_prob_hint)), lp)

        root.addView(fieldRow(getString(R.string.length_gradient_label)), lp)
        gradientEdit = singleLineField()
        gradientEdit.setText(current.lengthGradient.toString())
        root.addView(gradientEdit, lp)
        root.addView(caption(getString(R.string.length_gradient_hint)), lp)

        lengthCurve = LengthCurveView(this)
        val curveLp = LinearLayout.LayoutParams(MATCH_PARENT, dp(110)).apply { topMargin = dp(4) }
        root.addView(lengthCurve, curveLp)

        val updateLengthGateState = {
            val enabled = lengthGateEdit.text.toString().trim().toIntOrNull() ?: 0 > 0
            gradientEdit.isEnabled = enabled
            gradientEdit.alpha = if (enabled) 1f else 0.4f
            lengthCurve.setEnabled(enabled)
            lengthCurve.alpha = if (enabled) 1f else 0.4f
        }
        val gateWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateLengthGateState()
                refreshCurve()
            }
        }
        lengthGateEdit.addTextChangedListener(gateWatcher)
        gradientEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshCurve() }
        })
        updateLengthGateState()

        lengthAutoAdjustPoolSwitch = Switch(this).apply {
            isChecked = current.lengthAutoAdjustPool
            setText(R.string.length_auto_adjust_pool_label)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        root.addView(lengthAutoAdjustPoolSwitch, lp)
        root.addView(caption(getString(R.string.length_auto_adjust_pool_hint)), lp)

        root.addView(sectionDivider(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(3) })

        root.addView(fieldRow(getString(R.string.stability_label)), lp)
        stabilityEdit = singleLineField()
        stabilityEdit.setText(if (current.stability > 0) current.stability.toString() else "0")
        root.addView(stabilityEdit, lp)
        root.addView(caption(getString(R.string.stability_hint)), lp)

        root.addView(fieldRow(getString(R.string.stability_min_guaranteed_label)), lp)
        stabilityMinGuaranteedEdit = singleLineField()
        stabilityMinGuaranteedEdit.setText(current.stabilityMinGuaranteed.toString())
        root.addView(stabilityMinGuaranteedEdit, lp)
        root.addView(caption(getString(R.string.stability_min_guaranteed_hint)), lp)

        addSection(R.string.affix_section)
        // 口癖长什么样直接摆在说明这一行后面，别逼用户点进编辑框才看得到
        affixNote = caption("")
        root.addView(affixNote, lp)
        affixContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(affixContainer, lp)
        root.addView(addButton(getString(R.string.add_affix)) { showAffixEditor(null) }, lp)
        // 跟开关一样的一行：左边名字、右边当前值。点一下弹系统单选框，选项不常驻占地方。
        panguRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(10), 0, dp(10))
        }
        panguRow.addView(TextView(this).apply {
            setText(R.string.pangu_label)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panguValue = TextView(this).apply {
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 15f
        }
        panguRow.addView(panguValue, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(panguRow, lp)
        root.addView(caption(getString(R.string.pangu_hint)), lp)
        showPanguValue(current.panguMode)

        affixSpaceLatinLatin = switchRow(R.string.affix_space_latin_latin_label).apply {
            isChecked = current.affixSpaceLatinLatin
        }
        root.addView(caption(getString(R.string.affix_space_latin_latin_hint)), lp)
        root.addView(caption(getString(R.string.affix_space_neither_hint)), lp)
        botChinesePeriod = switchRow(R.string.bot_chinese_period_label).apply {
            isChecked = current.botChinesePeriod
        }
        root.addView(caption(getString(R.string.bot_chinese_period_hint)), lp)
        lineEndsSentence = switchRow(R.string.line_ends_sentence_label).apply {
            isChecked = current.lineEndsSentence
        }
        root.addView(caption(getString(R.string.line_ends_sentence_hint)), lp)
        affixTailTilde = switchRow(R.string.affix_tail_tilde_label).apply {
            isChecked = current.affixTailTilde
        }
        root.addView(caption(getString(R.string.affix_tail_tilde_hint)), lp)
        root.addView(fieldRow(getString(R.string.affix_repeat_penalty_label)), lp)
        affixRepeatPenaltyEdit = singleLineField().apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(formatPercent(current.affixRepeatPenalty))
        }
        root.addView(affixRepeatPenaltyEdit, lp)
        root.addView(caption(getString(R.string.affix_repeat_penalty_hint)), lp)

        root.addView(fieldRow(getString(R.string.scope_label)), lp)
        scopeWhole = CheckBox(this).apply {
            isChecked = current.scope == Scope.WHOLE_MESSAGE
            setText(R.string.scope_whole)
        }
        root.addView(scopeWhole, lp)
        root.addView(caption(getString(R.string.scope_sentence)), lp)
        allowPrefixSuffixTogether = switchRow(R.string.affix_mix_kinds_label).apply {
            isChecked = current.allowPrefixSuffixTogether
        }
        root.addView(caption(getString(R.string.affix_mix_kinds_hint)), lp)

        addSection(R.string.replace_section)
        root.addView(caption(getString(R.string.replace_note)), lp)
        replacementContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(replacementContainer, lp)
        root.addView(addButton(getString(R.string.add_replacement)) { showReplacementEditor(null) }, lp)

        addSection(R.string.advanced_section)
        val advancedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val advancedTitle = TextView(this).apply {
            setText(R.string.show_advanced)
            setTextColor(resources.getColor(R.color.accent, theme))
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        advancedHeader.addView(advancedTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(advancedHeader, lp)

        advancedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(advancedContainer, lp)

        antithesisSwitch = Switch(this).apply {
            setText(R.string.antithesis_label)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        advancedContainer.addView(antithesisSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        advancedContainer.addView(caption(getString(R.string.antithesis_hint)), lp)
        advancedContainer.addView(caption(getString(R.string.ignore_note)), lp)
        ignoreUrlSwitch = Switch(this).apply {
            setText(R.string.ignore_url)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        ignoreEmojiSwitch = Switch(this).apply {
            setText(R.string.ignore_emoji)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        ignoreRandomSwitch = Switch(this).apply {
            setText(R.string.ignore_random)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        advancedContainer.addView(ignoreUrlSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        advancedContainer.addView(ignoreEmojiSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        advancedContainer.addView(ignoreRandomSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        hideSessionNumberSwitch = Switch(this).apply {
            setText(R.string.hide_session_number)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        advancedContainer.addView(caption(getString(R.string.hide_session_number_hint)), lp)
        advancedContainer.addView(hideSessionNumberSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 会话作用域设置
        addSection(R.string.session_scope_section, advancedContainer)
        advancedContainer.addView(caption(getString(R.string.session_scope_hint)), lp)

        // 单聊、群聊各一套「白名单 / 黑名单」的选择，两类互不影响，可以一个用白名单、
        // 另一个用黑名单。同时各带一个名单展示区。
        fun modeLabel(textRes: Int) = TextView(this).apply {
            setText(textRes)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        fun modeRadio(textRes: Int) = RadioButton(this).apply {
            setText(textRes)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }

        advancedContainer.addView(modeLabel(R.string.single_scope_label), lp)
        singleListModeGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        singleListModeWhitelist = modeRadio(R.string.list_mode_whitelist)
        singleListModeBlacklist = modeRadio(R.string.list_mode_blacklist)
        singleListModeGroup.addView(singleListModeWhitelist, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        singleListModeGroup.addView(singleListModeBlacklist, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        advancedContainer.addView(singleListModeGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })

        advancedContainer.addView(modeLabel(R.string.group_scope_label), lp)
        groupListModeGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        groupListModeWhitelist = modeRadio(R.string.list_mode_whitelist)
        groupListModeBlacklist = modeRadio(R.string.list_mode_blacklist)
        groupListModeGroup.addView(groupListModeWhitelist, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        groupListModeGroup.addView(groupListModeBlacklist, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        advancedContainer.addView(groupListModeGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })

        // 名单本身搬到 [SessionListsActivity] 了，这里只留一个入口。四份名单摊在
        // 设置页里会把这一页拉得老长，而设置页本来是给开关和数字用的。
        advancedContainer.addView(
            Button(this).apply {
                text = getString(R.string.session_lists_entry)
                textSize = 15f
                gravity = Gravity.START
                setOnClickListener {
                    try {
                        // 包名写 QQ、类名写我们，和齿轮按钮同一个写法：设置页本身是被
                        // ActProxy 换过 Intent 拉起来的，在这里再 Intent(this, ...)
                        // 会用 QQ 当包名，QQ 的 PackageManager 里没有这条记录，直接
                        // ActivityNotFoundException。名单页也得在 ActProxy 的白名单里。
                        startActivity(
                            android.content.Intent()
                                .setClassName(
                                    ConfigBridge.HOST_PACKAGE,
                                    ConfigBridge.LISTS_ACTIVITY,
                                )
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (t: Throwable) {
                        Log.w(LOGTAG, "open lists page failed", t)
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.open_lists_failed_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            lp,
        )

        singleListModeGroup.setOnCheckedChangeListener { _, _ ->
            current = current.copy(
                singleListMode = if (singleListModeWhitelist.isChecked) ListMode.WHITELIST else ListMode.BLACKLIST)
        }
        groupListModeGroup.setOnCheckedChangeListener { _, _ ->
            current = current.copy(
                groupListMode = if (groupListModeWhitelist.isChecked) ListMode.WHITELIST else ListMode.BLACKLIST)
        }

        panguRow.setOnClickListener { showPanguPicker() }

        advancedHeader.setOnClickListener {
            val nowVisible = advancedContainer.visibility == View.VISIBLE
            advancedContainer.visibility = if (nowVisible) View.GONE else View.VISIBLE
            advancedTitle.setText(if (nowVisible) R.string.show_advanced else R.string.hide_advanced)
        }

        // 禁用关键词归到「详细设置」里：它是低频开关，摆在主页面只是把上面的概率/长度/
        // 稳定性挤下去，跟对仗、忽略规则、悬浮球样式放一起更整齐。
        addSection(R.string.block_section, advancedContainer)
        advancedContainer.addView(caption(getString(R.string.block_hint)), lp)
        blockKeywordsEdit = EditText(this).apply {
            textSize = 14f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        advancedContainer.addView(blockKeywordsEdit, lp)

        addSection(R.string.image_desc_section, advancedContainer)
        // 跟盘古一样：一行显示当前档位，点开是系统单选框。
        // 原来这里是三档单选（关 / 单独设置 / 用主表）。分类型之后每类能自己选表、还能配随机底句，
        // 中间那两档已经没有区别了，留着只会让人以为自己还漏了哪一档，所以收成一个总开关。
        imageDescSwitch = Switch(this).apply {
            setText(R.string.image_desc_master_label)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            isChecked = current.imageDescMode != ImageDescMode.OFF
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
            setOnCheckedChangeListener { _, checked ->
                current = current.copy(imageDescMode = if (checked) ImageDescMode.MAIN else ImageDescMode.OFF)
                showImageDescMode(current.imageDescMode)
            }
        }
        advancedContainer.addView(imageDescSwitch, lp)
        advancedContainer.addView(caption(getString(R.string.image_desc_affix_hint)), lp)

        // 每一类一行，同时摊开：类型名 + 一个下拉（跟主表一样 / 用公共口癖表 /
        // 随机底句（整条消息） / 关），下拉选什么，下面就把那张表和加它的输入框摆出来。
        // 不做成「先选类型再点按钮」那种两步 —— 那样四类的条目一次只能看一类，
        // 想确认另一类配成什么样就得来回切。
        imageDescKindBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        advancedContainer.addView(imageDescKindBox, lp)
        for (kind in CaptionKind.entries) {
            imageDescKindBox.addView(buildCaptionKindBlock(kind), lp)
        }
        // 公共口癖表：四类里选了「用公共口癖表」的都从这一张抽。就画一次，
        // 上面四行只写「用下面那张公共口癖表」。
        imageDescPublicBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        imageDescKindBox.addView(imageDescPublicBox, lp)
        renderPublicAffix()

        // 随机底句表：四类各一张，原来各自画在自己那一档底下。问题是只有先把那一类
        // 切成「随机底句」才看得到表，切走了连改都改不了 —— 想先配好再开的人
        // 只能来回切。这里跟公共口癖表一样收在下面对着四类，四张表一次全看见。
        imageDescBaseBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        imageDescKindBox.addView(imageDescBaseBox, lp)
        renderCaptionBases()
        showImageDescMode(current.imageDescMode)

        floatSwitch = Switch(this).apply {
            setText(R.string.float_enable)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
        }
        advancedContainer.addView(floatSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        advancedContainer.addView(caption(getString(R.string.float_style_label)), lp)

        floatSizeEdit = singleLineField()
        floatSizeEdit.setText(current.floatSizeDp.toString())
        advancedContainer.addView(floatSizeEdit, lp)
        advancedContainer.addView(caption(getString(R.string.float_style_size_hint)), lp)

        floatAlphaEdit = singleLineField()
        floatAlphaEdit.setText(current.floatAlpha.toString())
        advancedContainer.addView(floatAlphaEdit, lp)
        advancedContainer.addView(caption(getString(R.string.float_style_alpha_hint)), lp)

        val resetButton = Button(this).apply {
            setText(R.string.reset_config_label)
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setMessage("删除配置文件，将所有设置恢复为默认值。")
                    .setPositiveButton("OK") { _, _ ->
                        ConfigBridge.delete(this@SettingsActivity)
                        current = KuchiguseConfig()
                        loadIntoFields()
                        Toast.makeText(this@SettingsActivity, "已重置为默认值", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        advancedContainer.addView(resetButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val aboutButton = Button(this).apply {
            setText(R.string.about_label)
            setOnClickListener { showAboutDialog() }
        }
        advancedContainer.addView(aboutButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val saveButton = Button(this).apply {
            setText(R.string.save)
            setOnClickListener { save() }
        }
        val saveLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        root.addView(saveButton, saveLp)

        // 重新读取：回磁盘读一次，刷新 QQ 进程内的缓存，并把界面上的改动全部丢掉。
        // 设置页和 hook 在同一个进程里（ActProxy 拉起，没有 android:process），所以这里
        // 读到的就是 QQ 正在用的那一份。
        val reloadButton = Button(this).apply {
            setText(R.string.reload_config_label)
            setOnClickListener { reloadFromDisk() }
        }
        root.addView(
            reloadButton,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )

        return ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setBackgroundColor(resources.getColor(R.color.bg, theme))
        }
    }

    private fun addSection(strRes: Int, into: ViewGroup = root) {
        into.addView(TextView(this).apply {
            setText(strRes)
            setTextColor(resources.getColor(R.color.accent, theme))
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** 「读不到配置文件」的告警条：说清现状 + 给一个授权按钮。 */
    private fun configUnreadableBanner(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0x33FF9800.toInt())
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        box.addView(TextView(this).apply {
            setText(R.string.config_unreadable_title)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
        }, lp)
        box.addView(TextView(this).apply {
            setText(R.string.config_unreadable_hint)
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        }, lp)
        return box
    }

    /** 那一行右边显示当前档位。 */
    private fun showPanguValue(mode: PanguMode) {
        panguValue.setText(
            when (mode) {
                PanguMode.OFF -> R.string.pangu_mode_off
                PanguMode.AFFIX -> R.string.pangu_mode_affix
                PanguMode.WHOLE_MESSAGE -> R.string.pangu_mode_whole
            },
        )
    }

    private fun showImageDescMode(mode: ImageDescMode) {
        imageDescSwitch.isChecked = mode != ImageDescMode.OFF
        // 总开关关了，分类型那一摊全都用不上，整块收起来。
        imageDescKindBox.visibility = if (mode != ImageDescMode.OFF) View.VISIBLE else View.GONE
        captionKindViews.forEach { (kind, views) -> renderCaptionKindBody(kind, views) }
    }

    /** 系统单选框，跟这一页其它对话框同一套外观。选中即写进 [current]，跟名单模式那两个单选组一样。 */
    private fun showPanguPicker() {
        val labels = arrayOf(
            getString(R.string.pangu_mode_off),
            getString(R.string.pangu_mode_affix),
            getString(R.string.pangu_mode_whole),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.pangu_label)
            .setSingleChoiceItems(labels, panguOrder.indexOf(current.panguMode)) { dialog, which ->
                current = current.copy(panguMode = panguOrder[which])
                showPanguValue(current.panguMode)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(resources.getColor(R.color.text_secondary, theme))
        textSize = 11f
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
        setBackgroundColor(resources.getColor(R.color.divider, theme))
    }

    private fun sectionDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(2))
        setBackgroundColor(resources.getColor(R.color.accent, theme).let { Color.argb(100, Color.red(it), Color.green(it), Color.blue(it)) })
    }

    private fun fieldRow(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(resources.getColor(R.color.text_secondary, theme))
        textSize = 12f
        setPadding(0, dp(6), 0, dp(1))
    }

    private fun singleLineField(): EditText = EditText(this).apply {
        textSize = 15f
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun addButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        setText(text)
        background = GradientDrawable().apply {
            setColor(resources.getColor(R.color.accent, theme))
            cornerRadius = dp(8).toFloat()
        }
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }.also { b ->
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        b.layoutParams = lp
    }

    /**
     * 建一个开关行并直接挂到 [into] 上。
     *
     * 默认挂 [root]（主页面那一列）。详细设置里的开关必须显式传 advancedContainer，
     * 否则会被挂到主页面最顶上、跑到「详细设置」外面去。
     */
    private fun switchRow(strRes: Int, into: ViewGroup = root): Switch = Switch(this).apply {
        setText(strRes)
        setTextColor(resources.getColor(R.color.text_primary, theme))
        textSize = 15f
        buttonTintList = ColorStateList.valueOf(resources.getColor(R.color.accent, theme))
    }.also { into.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    private fun card(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(resources.getColor(R.color.card, theme))
            }
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        card.layoutParams = lp
        return card
    }

    /**
     * 一类一行：左边类型名，右边一个下拉选这一类怎么拿口癖。
     *
     * 下拉四档：跟主表一样 / 用这一类自己的口癖表 / 随机底句（整条消息）/ 关。
     * 原来是「开关 + 随机底句开关」两个布尔，能拼出四档但有一半说不清（关掉随机底句
     * 又关掉这一类，跟直接关这一类没区别），所以收成一个显式的四选一。
     *
     * 监听器是建完下拉、选好当前档之后才挂的：早挂一步，setSelection 自己就会触发一次
     * onItemSelected，把还没读完的配置先改掉。
     */
    private fun buildCaptionKindBlock(kind: CaptionKind): View {
        val views = CaptionKindViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            setText(kindLabel(kind))
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val modeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            CaptionMode.entries.map { getString(captionModeLabel(it)) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        views.modeSpinner = Spinner(this).apply {
            adapter = modeAdapter
            setSelection(current.captionKind(kind).mode.ordinal, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val mode = CaptionMode.entries.getOrNull(pos) ?: return
                    if (current.captionKind(kind).mode == mode) return
                    updateCaptionKind(kind) { it.copy(mode = mode) }
                    renderCaptionKindBody(kind, views)
                    // 下面那一块只列在用底句的类，改了档位得跟着重画。
                    renderCaptionBases()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        head.addView(views.modeSpinner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        box.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        views.body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(views.body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        captionKindViews[kind] = views
        renderCaptionKindBody(kind, views)
        return box
    }

    /**
     * 底下那块按当前档位重画。
     *
     * 现在四档底下都只是一句话：这一类用哪张表、或者关掉。两张表（公共口癖表、随机底句表）
     * 都画在下面那一块，四类共用同一处，所以这里没有表可画，也不用跟着表的变化重画。
     */
    private fun renderCaptionKindBody(kind: CaptionKind, views: CaptionKindViews) {
        val body = views.body
        body.removeAllViews()
        val cfg = current.captionKind(kind)
        val noteLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }

        when (cfg.mode) {
            CaptionMode.OFF -> body.addView(caption(getString(R.string.caption_kind_mode_off_note)), noteLp)

            CaptionMode.MAIN_AFFIX ->
                body.addView(caption(getString(R.string.caption_kind_mode_main_note)), noteLp)

            // 公共表就一张，画一次在下面对着四类那一块，这里只指路 —— 四条各画一遍
            // 就是同一张表出现四次，改一处其它三处也跟着变，看着像坏了。
            CaptionMode.AFFIX ->
                body.addView(caption(getString(R.string.caption_kind_affix_note)), noteLp)

            // 随机底句表也收在下面，跟公共口癖表一个待遇：这里只指路。
            // 原来表是画在这一档底下的，于是「先配好底句再开这一类」根本做不到 ——
            // 不开这一档就看不见表，开着又得先切档位，来回折腾。
            CaptionMode.RANDOM_WHOLE_MSG ->
                body.addView(caption(getString(R.string.caption_kind_base_note)), noteLp)
        }
    }

    private fun captionModeLabel(mode: CaptionMode): Int = when (mode) {
        CaptionMode.OFF -> R.string.caption_mode_off
        CaptionMode.MAIN_AFFIX -> R.string.caption_mode_main_affix
        CaptionMode.AFFIX -> R.string.caption_mode_affix
        CaptionMode.RANDOM_WHOLE_MSG -> R.string.caption_mode_random_whole
    }

    /**
     * 改某一类的配置。改完清一次抽签计数，免得旧计数按新配置继续算。
     *
     * 不动总闸 [KuchiguseConfig.imageDescMode]，只改 [KuchiguseConfig.imageDescKinds]：
     * 总闸关着时这一整块是隐藏的，点不到；配置被外部改成「总闸关 + 某类没关」时，
     * [KuchiguseConfig.captionEnabled] 也会拦下来，不会半开。
     */
    private fun updateCaptionKind(kind: CaptionKind, block: (CaptionKindConfig) -> CaptionKindConfig) {
        val next = current.imageDescKinds.toMutableMap()
        next[kind] = block(current.captionKind(kind))
        current = current.copy(imageDescKinds = next)
        KuchiguseEngine.resetAffixStreaks()
    }

    private fun renderCaptionBaseCard(into: LinearLayout, text: String, onClick: () -> Unit, onDelete: () -> Unit) {
        val card = card()
        card.setOnClickListener { onClick() }
        card.addView(TextView(this).apply {
            this.text = text
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
        })
        card.addView(deleteLabel(onDelete))
        into.addView(card)
    }

    private fun showCaptionBaseEditor(kind: CaptionKind, target: Int?, onChanged: () -> Unit) {
        val list = current.captionKind(kind).bases
        val existing = target?.let { list[it] }
        val edit = singleLineField().apply { hint = getString(R.string.caption_base_placeholder) }
        edit.setText(existing.orEmpty())
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_caption_base else R.string.edit_caption_base)
            .setView(edit)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val next = list.toMutableList()
                if (target == null) next.add(text) else next[target] = text
                updateCaptionKind(kind) { it.copy(bases = next) }
                onChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 改公共口癖表。这一张四类共用，所以改完要把四类的行也重画一遍（AFFIX 那行的提示不变，不用）。 */
    private fun showPublicAffixEditor(target: Int?, onChanged: () -> Unit) {
        val list = current.imageDescAffixes
        val existing = target?.let { list[it] }
        editAffix(existing) { item ->
            val next = list.toMutableList()
            if (target == null) next.add(item) else next[target] = item
            current = current.copy(imageDescAffixes = next)
            KuchiguseEngine.resetAffixStreaks()
            onChanged()
        }
    }

    /** 公共口癖表那一块：标题、留空提示、表本身、加一条。 */
    private fun renderPublicAffix() {
        val box = imageDescPublicBox
        box.removeAllViews()
        val wm = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        box.addView(fieldRow(getString(R.string.caption_public_affix_label)), wm)
        box.addView(caption(getString(R.string.caption_public_affix_hint)), wm)
        if (current.imageDescAffixes.isEmpty()) {
            box.addView(caption(getString(R.string.caption_public_affix_empty)), wm)
        } else {
            val total = sumWeights(current.imageDescAffixes)
            current.imageDescAffixes.forEachIndexed { idx, item -> renderPublicAffix(item, idx, total) }
        }
        box.addView(
            addButton(getString(R.string.add_affix)) { showPublicAffixEditor(null) { renderPublicAffix() } },
            wm,
        )
    }

    /**
     * 随机底句表：设置页上只有这一行，**表本身在 dialog 里**。
     *
     * 四张表全摊在页面上会把这一段撑得很长，找别的设置得划很久；每张藏在自己那一档底下
     * 又是另一个毛病（不开那一档看不见表，「先配好再开」做不到）。收成一行 + dialog 两头都占：
     * 页面上永远只占一行高度，点开才在弹窗里改。
     *
     * 只列档位是「随机底句（整条消息）」的类：没在用的那些改了也不生效，摆出来是误导。
     */
    private fun renderCaptionBases() {
        // 这一行是 `lateinit`，而下拉框的 `onItemSelected` 在建 `imageDescKindBox` 那圈之前
        // 就挂上了。档位没变时回调虽然会在守卫那行 return，但依赖这个顺序太脆 ——
        // 哪天下拉多调一次重建，这里就是 UninitializedPropertyAccessException 崩设置页。
        if (!::imageDescBaseBox.isInitialized) return
        val box = imageDescBaseBox
        box.removeAllViews()
        val wm = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val inUse = CaptionKind.entries.filter { current.captionKind(it).mode == CaptionMode.RANDOM_WHOLE_MSG }
        if (inUse.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(dialogRow(getString(R.string.caption_base_section_label), inUse.size) {
            showCaptionBaseDialog()
        }, wm)
    }

    /**
     * 可点的一行：左边标题带数量，右边一个「点开」。点开是 dialog，列表不在这一行下面展开。
     *
     * 叫 `dialogRow` 而不是 `collapsibleHeader`：这一行没有展开/收起两态，
     * 叫 collapsible 会让人以为点一下能在这行下面就地摊开。
     */
    private fun dialogRow(title: String, count: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(1))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = "$title（$count）"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            setText(R.string.show_list)
            setTextColor(resources.getColor(R.color.accent, theme))
            textSize = 12f
        })
        return row
    }

    /**
     * 随机底句表的编辑弹窗。在用底句的类各一段：类型名、表、加一条。
     *
     * 自己往 dialog 里塞一个 ScrollView，而不是走 `setItems` 那种列表对话框：每一段下面
     * 还要带一个输入框和按钮，`setItems` 放不下。
     *
     * ScrollView 是必需的，不是保险：只调 `setView` 而不调 `setMessage` 时，AlertDialog
     * 自己不会给自定义视图套滚动容器，内容一多就直接被截掉，底下的加一条输入框点不到。
     */
    private fun showCaptionBaseDialog() {
        val inUse = CaptionKind.entries.filter { current.captionKind(it).mode == CaptionMode.RANDOM_WHOLE_MSG }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 每一类输入框里打了还没提交的字，存这里。
        // 下面 add/删/改都要 `fill()` 整个重建，重建会连输入框一起换掉 —— 不存回来的话，
        // 在图片那一类打了半句，再点表情那一类的卡片改一句，回来就没了。
        val pending = mutableMapOf<CaptionKind, String>()
        fun fill() {
            content.removeAllViews()
            val wm = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            inUse.forEachIndexed { i, kind ->
                if (i > 0) content.addView(divider(), wm)
                fillBaseGroup(kind, content, wm, pending) { fill() }
            }
        }
        fill()
        // 高度用 WRAP_CONTENT，不让 AlertDialog 塞 MATCH_PARENT：那个会让弹窗一打开就顶满屏，
        // 只有两三句底句时看着像误触了全屏。内容超了屏，ScrollView 自己滚。
        val scroller = ScrollView(this).apply {
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.caption_base_section_label)
            .setView(scroller)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 弹窗里一类的那一段：类型名、表、留空提示或表本身、加一条的输入框。
     *
     * [pending] 是这一类输入框还没提交的字：外面重建时会回填进来，加一条成功后清掉。
     */
    private fun fillBaseGroup(
        kind: CaptionKind,
        into: LinearLayout,
        wm: LinearLayout.LayoutParams,
        pending: MutableMap<CaptionKind, String>,
        onChanged: () -> Unit,
    ) {
        val cfg = current.captionKind(kind)
        // 类型名必须挂上：几张表长得一模一样，不标出来根本分不清哪句配的是哪一类。
        into.addView(fieldRow(getString(kindLabel(kind))), wm)
        if (cfg.bases.isEmpty()) {
            into.addView(caption(getString(R.string.caption_base_fallback)), wm)
        } else {
            cfg.bases.forEachIndexed { idx, text ->
                renderCaptionBaseCard(
                    into,
                    text,
                    onClick = { showCaptionBaseEditor(kind, idx) { onChanged() } },
                    onDelete = {
                        updateCaptionKind(kind) { c -> c.copy(bases = c.bases.filterIndexed { k, _ -> k != idx }) }
                        onChanged()
                    },
                )
            }
        }
        val input = singleLineField().apply {
            hint = getString(R.string.caption_base_placeholder)
            setText(pending[kind].orEmpty())
            setSelection(pending[kind].orEmpty().length)
        }
        // 输入框里的字随时记进 [pending]，不然重建时只能靠外面那次回填 ——
        // 用户打完字直接点别的类的卡片时，中间这一刻的值还在这个 EditText 里。
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                pending[kind] = s?.toString().orEmpty()
            }
        })
        // 间距写在新建的 LayoutParams 上，不要就地改 [wm]：这个 [wm] 是 [fill] 一次建出来
        // 传给所有分组的共用对象，改了它同一次弹窗里后面所有分组都会跟着多出这段上边距。
        into.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        into.addView(
            addButton(getString(R.string.add_caption_base)) {
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@addButton
                updateCaptionKind(kind) { it.copy(bases = it.bases + text) }
                pending.remove(kind)
                onChanged()
            },
            wm,
        )
    }

    private fun renderPublicAffix(item: AffixItem, idx: Int, totalWeight: Int) {
        renderAffixCardInto(
            imageDescPublicBox,
            item,
            totalWeight,
            onClick = { showPublicAffixEditor(idx) { renderPublicAffix() } },
            onDelete = {
                current = current.copy(
                    imageDescAffixes = current.imageDescAffixes.filterIndexed { k, _ -> k != idx },
                )
                KuchiguseEngine.resetAffixStreaks()
                renderPublicAffix()
            },
        )
    }

    private fun kindLabel(kind: CaptionKind): Int = when (kind) {
        CaptionKind.PICTURE -> R.string.caption_kind_picture
        CaptionKind.ANIMATED_EMOJI -> R.string.caption_kind_animated
        CaptionKind.EMOJI -> R.string.caption_kind_emoji
        CaptionKind.FACE_BUBBLE -> R.string.caption_kind_face_bubble
    }

    /** 一类的那一行控件。建的时候填进 [captionKindViews]，之后按类型取。 */
    private class CaptionKindViews {
        lateinit var modeSpinner: Spinner
        lateinit var body: LinearLayout
    }

    /** 卡片右下角那个「删除」。点它只删，不进编辑器 —— 卡片本体才是编辑入口。 */
    private fun deleteLabel(onDelete: () -> Unit): TextView = TextView(this).apply {
        text = getString(R.string.delete)
        setTextColor(resources.getColor(R.color.accent, theme))
        textSize = 14f
        setPadding(dp(12), 0, 0, 0)
        setOnClickListener { onDelete() }
    }

    /** 主表的口癖编辑器。 */
    private fun showAffixEditor(target: Int?) {
        val list = current.affixes
        val existing = target?.let { list[it] }
        editAffix(existing) { item ->
            val edited = list.toMutableList()
            if (target == null) edited.add(item) else edited[target] = item
            current = current.copy(affixes = edited)
            KuchiguseEngine.resetAffixStreaks()
            refreshAffixes()
        }
    }

    /**
     * 口癖表单本体，回调拿到编好的那一条。
     *
     * 主表和分类型那份表用的是同一张表，拆出来是为了 UI 那边不用关心它写到哪张表上去。
     */
    private fun editAffix(existing: AffixItem?, onSave: (AffixItem) -> Unit) {
        val kindGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val prefixRb = RadioButton(this).apply { setText(R.string.kind_prefix) }
        val suffixRb = RadioButton(this).apply { setText(R.string.kind_suffix) }
        kindGroup.addView(prefixRb, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 1f))
        kindGroup.addView(suffixRb, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 1f))
        if (existing == null) suffixRb.isChecked = true else {
            if (existing.kind == AffixKind.PREFIX) prefixRb.isChecked = true else suffixRb.isChecked = true
        }
        val textEdit = EditText(this).apply { hint = getString(R.string.affix_text) }
        textEdit.setText(existing?.text.orEmpty())
        val weightEdit = EditText(this).apply {
            hint = getString(R.string.affix_weight)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        weightEdit.setText((existing?.weight ?: 100).toString())

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        form.addView(kindGroup)
        form.addView(textEdit)
        form.addView(weightEdit)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_affix else R.string.edit_affix)
            .setView(form)
            .setPositiveButton(R.string.ok) { _, _ ->
                val kind = if (prefixRb.isChecked) AffixKind.PREFIX else AffixKind.SUFFIX
                val text = textEdit.text.toString().trim()
                if (text.isEmpty()) {
                    // 以前直接 return：对话框照常关掉，什么都没加，用户以为存上了
                    Toast.makeText(this, "口癖不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val weight = weightEdit.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 100
                onSave(AffixItem(kind, text, weight))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshList() {
        refreshAffixes()
        refreshReplacements()
    }

    private fun refreshAffixes() {
        affixContainer.removeAllViews()
        if (current.affixes.isEmpty()) {
            affixNote.text = getString(R.string.affix_note) + "\n" + getString(R.string.affix_empty)
            return
        }
        // 汇总跟在说明后面。权重不重复：每张卡片上本来就写着「权重 100」
        affixNote.text = getString(R.string.affix_note) + "\n" + current.affixes.joinToString("  ") { item ->
            val kind = getString(if (item.kind == AffixKind.PREFIX) R.string.prefix_short else R.string.suffix_short)
            "$kind「${item.text}」"
        }
        // 总和算一次给所有卡片用，别在循环里每张算一遍。
        val total = sumWeights(current.affixes)
        current.affixes.forEachIndexed { idx, item ->
            renderAffixCard(item, idx, total) { i ->
                current = current.copy(affixes = current.affixes.filterIndexed { k, _ -> k != i })
                KuchiguseEngine.resetAffixStreaks()
                refreshAffixes()
            }
        }
    }

    /**
     * 一张口癖卡片。点卡片进编辑器，点删除调 [onDelete]。
     *
     * [totalWeight] 由调用方一次算好传进来：每张卡片都重算一遍总和就是 O(n²)，
     * 而这一屏卡片本来就是同一张表的。
     */
    private fun renderAffixCard(item: AffixItem, idx: Int, totalWeight: Int, onDelete: (Int) -> Unit) {
        renderAffixCardInto(affixContainer, item, totalWeight, onClick = { showAffixEditor(idx) }, onDelete = { onDelete(idx) })
    }

    /** 一张表的权重总和。权重最低按 1 算，跟 [KuchiguseEngine.pickWeighted] 里 `coerceAtLeast(1)` 对齐。 */
    private fun sumWeights(items: List<AffixItem>): Int = items.sumOf { it.weight.coerceAtLeast(1) }

    /**
     * 这一条的基准占比，一位小数，整数不带 `.0`。
     *
     * **这是近似值，不是实时概率。** 实测（4 万次抽取、重复削减关掉）：[KuchiguseEngine.pickWeighted]
     * 除了权重，还按「连续没被抽中往上加权」把分布往均匀方向拉，所以低权重那几条显示值会明显
     * 低于实际 —— 权重 25:75 实际是 30.9:69.1，7 条里 3.6% 的那条实际能到 8.7%。
     * 前面那个 `≈` 就是不想让人把它当准数；它的用处是看出权重有没有填反、填成了 0。
     *
     * 带上 [Locale.ROOT]：设备语言用逗号作小数点时，`String.format` 默认会印出 `33,3%`。
     */
    private fun sharePercent(weight: Int, totalWeight: Int): String {
        val s = String.format(Locale.ROOT, "%.1f%%", weight.coerceAtLeast(1) * 100.0 / totalWeight)
        return if (s.endsWith(".0%")) s.dropLast(2) + "%" else s
    }

    private fun renderAffixCardInto(
        into: LinearLayout,
        item: AffixItem,
        totalWeight: Int,
        onClick: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val card = card()
        card.setOnClickListener { onClick() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tag = TextView(this).apply {
            text = if (item.kind == AffixKind.PREFIX) getString(R.string.prefix_short) else getString(R.string.suffix_short)
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (item.kind == AffixKind.PREFIX) 0xFF4F5AF5.toInt() else 0xFF26A69A.toInt())
            }
        }
        row.addView(tag, LinearLayout.LayoutParams(dp(24), dp(24)))
        // 口癖和权重同一行：权重原来自己占一行，每条卡片都因此高出一截，扫一屏
        // 下来真正要认的那句话反而被挤成一条线。收在右边同行，一眼还能对上是哪条。
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
        }
        body.addView(TextView(this).apply {
            text = item.text
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(TextView(this).apply {
            text = getString(R.string.weight_of, item.weight)
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        })
        // 权重后面跟上这一条在表里的占比。光看权重看不出「100」是 1% 还是 50% ——
        // 权重是相对值，只有除以全表总和才知道大概多大概率被抽中。
        body.addView(TextView(this).apply {
            text = getString(R.string.affix_share, sharePercent(item.weight, totalWeight))
            setTextColor(resources.getColor(R.color.accent, theme))
            textSize = 12f
            setPadding(dp(6), 0, 0, 0)
        })
        row.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val del = Button(this).apply {
            text = getString(R.string.delete)
            textSize = 12f
            setTextColor(resources.getColor(R.color.danger, theme))
            background = null
            setOnClickListener { onClick() }
        }
        row.addView(del)
        card.addView(row)
        into.addView(card)
    }

    private fun refreshReplacements() {
        replacementContainer.removeAllViews()
        if (current.replacements.isEmpty()) {
            replacementContainer.addView(caption(getString(R.string.replace_empty)))
            return
        }
        current.replacements.forEachIndexed { idx, r ->
            val card = card()
            card.setOnClickListener { showReplacementEditor(idx) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.from_to, r.from, r.to)
                setTextColor(resources.getColor(R.color.text_primary, theme))
                textSize = 15f
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val del = Button(this).apply {
                text = getString(R.string.delete)
                textSize = 12f
                setTextColor(resources.getColor(R.color.danger, theme))
                background = null
                setOnClickListener {
                    current = current.copy(replacements = current.replacements.filterIndexed { i, _ -> i != idx })
                    refreshReplacements()
                }
            }
            row.addView(del)
            card.addView(row)
            replacementContainer.addView(card)
        }
    }


    private fun showReplacementEditor(target: Int?) {
        val existing = target?.let { current.replacements[it] }
        val fromEdit = EditText(this).apply { hint = getString(R.string.replace_from) }
        fromEdit.setText(existing?.from.orEmpty())
        val toEdit = EditText(this).apply { hint = getString(R.string.replace_to) }
        toEdit.setText(existing?.to.orEmpty())

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        form.addView(fromEdit)
        form.addView(toEdit)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_replacement else R.string.edit_replacement)
            .setView(form)
            .setPositiveButton(R.string.ok) { _, _ ->
                val from = fromEdit.text.toString()
                val to = toEdit.text.toString()
                if (from.isEmpty()) return@setPositiveButton
                val r = Replacement(from, to)
                val list = current.replacements.toMutableList()
                if (target == null) list.add(r) else list[target] = r
                current = current.copy(replacements = list)
                refreshReplacements()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCurve() {
        val maxLen = lengthGateEdit.text.toString().trim().toIntOrNull() ?: current.maxLength
        val grad = gradientEdit.text.toString().trim().toDoubleOrNull() ?: current.lengthGradient
        lengthCurve.setParams(maxLen, grad)
    }

    private fun runTester() {
        val raw = testerInput.text.toString()
        if (raw.isBlank()) return
        if (testerActive) { cancelTester(); return }

        val engine = KuchiguseEngine
        val previews = engine.previewChunks(raw, current)
        testerOriginal = raw
        testerActive = true

        val ss = android.text.SpannableStringBuilder()
        val pink = 0xFFFF7FA8.toInt()
        previews.forEach { p ->
            // 一条预览自带自己的前后空白（换行、行尾空格），渲染前先摘出来。
            // 直接拿整条去找位置的话，「%」会被拼到换行之后、显示到下一行，命中时
            // 的粉色也会因为尾巴上还挂着换行而定位不到。
            val (lead, rawBody, trail) = engine.splitChunkWhitespace(p.text)
            if (rawBody.isEmpty()) {
                // 整块都是空白（空行），原样显示
                ss.append(p.text)
            } else if (p.hit) {
                // 命中：仅把新增的前后缀文本染成粉色，其余保持原色
                val body = p.result.removePrefix(lead).removeSuffix(trail)
                ss.append(lead)
                val start = ss.length
                ss.append(body)
                // 用这一条预览自己报上来的口癖定位，不要另外抽一次去猜
                val affixText = p.affix?.text.orEmpty()
                if (affixText.isNotEmpty()) {
                    val affixStart: Int
                    val affixEnd: Int
                    if (p.affix?.kind == AffixKind.PREFIX) {
                        // 前缀在句身开头
                        affixStart = start
                        affixEnd = start + affixText.length
                    } else {
                        // 后缀在句身的 emoji 串与标点之前（句身之外的换行不算）
                        val punct = engine.trailingPunct(body)
                        val head = body.removeSuffix(punct)
                        val core = head.removeSuffix(engine.trailingEmojiRun(head))
                        if (core.endsWith(affixText)) {
                            affixStart = start + core.length - affixText.length
                            affixEnd = start + core.length
                        } else {
                            affixStart = start
                            affixEnd = start
                        }
                    }
                    if (affixEnd > affixStart) {
                        ss.setSpan(
                            android.text.style.ForegroundColorSpan(pink),
                            affixStart, affixEnd,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ss.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            affixStart, affixEnd,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                ss.append(trail)
            } else if (p.probability > 0) {
                // 可能被处理但本次未命中：句身 + 粉色概率，概率标在这一句自己的行尾
                val punct = engine.trailingPunct(rawBody)
                val head = rawBody.removeSuffix(punct)
                val emojiTail = engine.trailingEmojiRun(head)
                val core = head.removeSuffix(emojiTail)
                ss.append(lead)
                ss.append(core)
                val start = ss.length
                ss.append(java.lang.String.format("%.0f%%", p.probability * 100))
                ss.setSpan(
                    android.text.style.ForegroundColorSpan(pink),
                    start, ss.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ss.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, ss.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ss.append(emojiTail)
                ss.append(punct)
                ss.append(trail)
            } else {
                // 不该处理的地方：不标概率，原样显示
                ss.append(p.text)
            }
        }
        testerInput.setText(ss)
        lockTester(true)
        testerButton.setText(R.string.tester_cancel)
    }

    private fun lockTester(locked: Boolean) {
        testerInput.isFocusableInTouchMode = !locked
        testerInput.isFocusable = !locked
        testerInput.isEnabled = !locked
        testerInput.isClickable = locked
        testerInput.setOnClickListener { if (locked) cancelTester() }
        testerInput.setShowSoftInputOnFocus(!locked)
    }

    private fun cancelTester() {
        if (testerActive) testerInput.setText(testerOriginal)
        testerActive = false
        lockTester(false)
        testerButton.setText(R.string.tester_run)
    }

    /**
     * 丢掉界面上没保存的改动，重新读盘并刷新进程内缓存。
     *
     * 缓存只在用户手动点这个按钮时才回到磁盘，所以「改了 QQ 目录里的文件/重装了配置」
     * 之后，得有个明确的入口来重读——原来这个按钮在悬浮面板上，随手一点就丢改动，
     * 挪到设置页并且在提示里说清代价。
     */
    private fun reloadFromDisk() {
        val fresh = KuchiguseCore.syncConfigNow(this)
        loadIntoFields()
        Toast.makeText(this, "已重新读取：" + if (fresh.enabled) "启用中" else "已停用", Toast.LENGTH_SHORT).show()
    }

    private fun loadIntoFields() {
        enableSwitch.isChecked = current.enabled
        probabilityEdit.setText(current.probabilityExpr)
        lengthGateEdit.setText(current.maxLength.toString())
        gradientEdit.setText(current.lengthGradient.toString())
        stabilityEdit.setText(if (current.stability > 0) current.stability.toString() else "0")
        affixRepeatPenaltyEdit.setText(formatPercent(current.affixRepeatPenalty))
        stabilityMinGuaranteedEdit.setText(current.stabilityMinGuaranteed.toString())
        lengthAutoAdjustPoolSwitch.isChecked = current.lengthAutoAdjustPool
        scopeWhole.isChecked = current.scope == Scope.WHOLE_MESSAGE
        allowPrefixSuffixTogether.isChecked = current.allowPrefixSuffixTogether
        showPanguValue(current.panguMode)
        affixSpaceLatinLatin.isChecked = current.affixSpaceLatinLatin
        botChinesePeriod.isChecked = current.botChinesePeriod
        lineEndsSentence.isChecked = current.lineEndsSentence
        affixTailTilde.isChecked = current.affixTailTilde
        ignoreUrlSwitch.isChecked = current.ignoreUrl
        ignoreEmojiSwitch.isChecked = current.ignoreEmoji
        ignoreRandomSwitch.isChecked = current.ignoreRandomLike
        hideSessionNumberSwitch.isChecked = current.hideSessionNumber
        antithesisSwitch.isChecked = current.antithesisEnabled
        floatSwitch.isChecked = current.floatEnabled
        floatSizeEdit.setText(current.floatSizeDp.toString())
        floatAlphaEdit.setText(current.floatAlpha.toString())
        blockKeywordsEdit.setText(current.blockKeywords.joinToString("\n"))
        showImageDescMode(current.imageDescMode)
        // 每一类的下拉和底下那张表都得跟着重新读一遍：配置可能是从文件那边换过来的。
        // 先把下拉全部设好，再统一重画下面两块：`setSelection` 会触发 `onItemSelected`，
        // 那个回调里已经会调 `renderCaptionBases()`，让它一次跑在最终档位上。
        captionKindViews.forEach { (kind, views) ->
            views.modeSpinner.setSelection(current.captionKind(kind).mode.ordinal, false)
        }
        captionKindViews.forEach { (kind, views) ->
            renderCaptionKindBody(kind, views)
        }
        renderPublicAffix()
        // 兜底重画一次：下拉的值没变时 `onItemSelected` 会在守卫那行直接 return，
        // 回调不跑，这一行就还是从文件里读之前的样子。
        renderCaptionBases()
        singleListModeWhitelist.isChecked = current.singleListMode == ListMode.WHITELIST
        singleListModeBlacklist.isChecked = current.singleListMode == ListMode.BLACKLIST
        groupListModeWhitelist.isChecked = current.groupListMode == ListMode.WHITELIST
        groupListModeBlacklist.isChecked = current.groupListMode == ListMode.BLACKLIST
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        val before = configLoad
        configLoad = ConfigBridge.load(ConfigBridge.appFile(this))
        // 用户点「去授权」跳到系统设置再回来，读得到性可能变了；变了就重建界面，
        // 否则告警条会一直挂在上面，而下面的字段已经是真实配置了。
        if ((before is ConfigBridge.Load.Unreadable) != (configLoad is ConfigBridge.Load.Unreadable)) {
            recreate()
            return
        }
        current = (configLoad as? ConfigBridge.Load.Ok)?.cfg ?: KuchiguseConfig()
        loadIntoFields()
    }

    override fun onDestroy() {
        // 设置页现在跑在 QQ 进程里，QQ 自己就常驻着一大堆东西；这里再漏一批 EditText
        // （每个都带着字体、CursorDrawable、InputConnection）就太浪费了。整棵 View 树
        // 主动从 window 上摘掉，让这一页的内存立刻变成可回收的垃圾，而不是等 GC 挑时机。
        detachContentView()
        super.onDestroy()
        if (testerActive) {
            testerInput.setText(testerOriginal)
            testerActive = false
        }
        // 自己都是垃圾了，就别再让 Handler 的待办队列攥着这些 View 的引用。
        mainHandler.removeCallbacksAndMessages(null)
        // 主动催一次回收：这一页的 View 树刚被整棵丢掉，等 GC 自然跑会白占一阵内存。
        // 只在低内存档位才 hint，避免无谓的 GC 抖动。
        if (Build.VERSION.SDK_INT >= 21) {
            val am = getSystemService(android.app.ActivityManager::class.java)
            if (am != null && am.isLowRamDevice) {
                System.gc()
            }
        }
    }

    /** 把 content view 置空并清掉根容器引用，让整棵 View 树可被 GC 回收。 */
    private fun detachContentView() {
        try {
            val view: View? = findViewById(android.R.id.content)
            view?.let { (it as? ViewGroup)?.removeAllViews() }
        } catch (t: Throwable) {
            Log.w(LOGTAG, "detach content view failed", t)
        }
    }

    private fun save() {
        // copy() on purpose: building a fresh KuchiguseConfig here silently reset
        // every field this screen does not render, including the per-conversation
        // overrides the bubble panel manages.
        //
        // Those overrides are also *concurrently* editable from the bubble panel
        // inside QQ, so re-read them from disk instead of trusting the snapshot
        // this screen was opened with -- otherwise saving here silently reverts
        // whatever the user toggled in the panel in the meantime.
        val onDisk = ConfigBridge.read(ConfigBridge.appFile(this))
        // 四份名单归 [SessionListsActivity] 和 QQ 里的悬浮面板管，这一页不再动它们，
        // 原样带上磁盘值，免得在这里存个盘把人家刚删的条目又还回去。
        val mergedSingleWhitelist = onDisk.singleWhitelist
        val mergedSingleBlacklist = onDisk.singleBlacklist
        val mergedGroupWhitelist = onDisk.groupWhitelist
        val mergedGroupBlacklist = onDisk.groupBlacklist
        val cfg = current.copy(
            enabled = if (masterSwitchTouched) enableSwitch.isChecked else onDisk.enabled,
            probabilityExpr = probabilityEdit.text.toString().trim().ifEmpty { "1" },
            scope = if (scopeWhole.isChecked) Scope.WHOLE_MESSAGE else Scope.PER_SENTENCE,
            affixes = current.affixes,
            affixRepeatPenalty = affixRepeatPenaltyEdit.text.toString().toDoubleOrNull()
                ?.coerceIn(0.0, 99.0) ?: 20.0,
            allowPrefixSuffixTogether = allowPrefixSuffixTogether.isChecked,
            affixSpaceLatinLatin = affixSpaceLatinLatin.isChecked,
            botChinesePeriod = botChinesePeriod.isChecked,
            lineEndsSentence = lineEndsSentence.isChecked,
            affixTailTilde = affixTailTilde.isChecked,
            replacements = current.replacements,
            ignoreUrl = ignoreUrlSwitch.isChecked,
            ignoreEmoji = ignoreEmojiSwitch.isChecked,
            ignoreRandomLike = ignoreRandomSwitch.isChecked,
            hideSessionNumber = hideSessionNumberSwitch.isChecked,
            // 空字段要退回当前值，不能退回 0 —— 0 的含义是「关闭长度门槛」，手滑清空输入框
            // 不应该把整个门槛静默关掉。
            maxLength = lengthGateEdit.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: current.maxLength,
            lengthGradient = gradientEdit.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0,
            stability = stabilityEdit.text.toString().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.4,
            stabilityMinGuaranteed = stabilityMinGuaranteedEdit.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 3,
            lengthAutoAdjustPool = lengthAutoAdjustPoolSwitch.isChecked,
            antithesisEnabled = antithesisSwitch.isChecked,
            blockKeywords = blockKeywordsEdit.text.toString()
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
            imageDescMode = current.imageDescMode,
            imageDescAffixes = current.imageDescAffixes,
            floatEnabled = floatSwitch.isChecked,
            floatSizeDp = floatSizeEdit.text.toString().toIntOrNull()?.coerceAtLeast(10) ?: 20,
            floatAlpha = floatAlphaEdit.text.toString().toFloatOrNull()?.coerceIn(0.0f, 1.0f) ?: 0.5f,
            // 会话作用域：这些现在在设置页里直接改，用 UI 上的值
            singleListMode = if (singleListModeWhitelist.isChecked) ListMode.WHITELIST else ListMode.BLACKLIST,
            groupListMode = if (groupListModeWhitelist.isChecked) ListMode.WHITELIST else ListMode.BLACKLIST,
            singleWhitelist = mergedSingleWhitelist,
            singleBlacklist = mergedSingleBlacklist,
            groupWhitelist = mergedGroupWhitelist,
            groupBlacklist = mergedGroupBlacklist,
        )
        current = cfg
        val ok = ConfigBridge.writeFromApp(this, cfg)
        if (ok) {
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.save_failed_toast, Toast.LENGTH_LONG).show()
        }
    }


    private fun showAboutDialog() {
        val version = versionLine()
        // 版本放最上面：正文第一行现在是功能描述，模块名已经由标题占了，版本号是最该被
        // 一眼扫到的信息，堆在最下面等于没有。
        val message = if (version.isEmpty()) {
            getString(R.string.about_dialog_message)
        } else {
            version + "\n\n" + getString(R.string.about_dialog_message)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

        /**
     * 「版本名 + versionCode」一行，都取自 [BuildConfig]。
     *
     * 不用 PackageManager：这页在 QQ 进程里用 QQ 的 Context 拉起来，
     * `getPackageInfo(getPackageName(), 0)` 拿到的是**宿主 QQ** 的版本；换成模块包名又查不到，
     * 因为 QQ 未必知道本模块存在（没装、被 HMA 藏起来），会抛 NameNotFound 让整行消失。
     * BuildConfig 是编译期常量，恒定可用。
     */
    private fun versionLine(): String {
        val name = BuildConfig.VERSION_NAME
        if (name.isEmpty()) return ""
        return getString(R.string.about_version_line, name)
    }

    private fun readConfig(): KuchiguseConfig = ConfigBridge.read(ConfigBridge.appFile(this))
}