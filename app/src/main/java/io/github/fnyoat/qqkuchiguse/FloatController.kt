/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge
import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig
import io.github.fnyoat.qqkuchiguse.config.ListMode
import io.github.fnyoat.qqkuchiguse.hook.KuchiguseCore
import io.github.fnyoat.qqkuchiguse.hook.Reflect
import io.github.fnyoat.qqkuchiguse.hook.SessionKeys
import io.github.fnyoat.qqkuchiguse.hook.SessionTracker
import java.io.File
import java.lang.ref.WeakReference
import org.json.JSONObject

/**
 * QQ 进程内的悬浮球。非系统 overlay：用宿主 Activity 的 WindowManager 以 TYPE_APPLICATION_PANEL
 * 添加，无需 SYSTEM_ALERT_WINDOW 权限，随应用窗口生命周期。
 *
 * 显示/隐藏由 hook 驱动的信号控制，顶层 Activity 经反射 `ActivityThread.mActivities` 取。配置
 * 默认用进程启动时缓存的那份，用户点「立刻重载配置」才重读；面板里的开关是用户显式操作，
 * 直接写回同一份配置文件。
 */
object FloatController {

    private const val TAG = "KuchiguseFloat"
    private val mMainHandler = Handler(Looper.getMainLooper())

    @Volatile private var mAttachedActivity: Activity? = null
    @Volatile private var mIcon: ImageView? = null
    private var mWindowParams: WindowManager.LayoutParams? = null
    private var mWindowManager: WindowManager? = null

    @Volatile private var mCurrentSessionKey: String? = null

    /** 当前会话是不是群。分类开关要用；取值器和接触对象都是缓存的，重复问不贵。 */
    private fun currentIsGroup(): Boolean =
        SessionTracker.isGroupChat(SessionTracker.currentContact)
    private var mDialog: Dialog? = null
    private var mSessionKeyCallback: ((String) -> Unit)? = null

    // 触摸状态（OnTouchListener 风格）
    private var mLastX = 0
    private var mLastY = 0

    @Volatile private var mChatVisible = false
    @Volatile private var mChatAlive = false
    @Volatile private var mTempHidden = false

    /**
     * 面板开着时的 500ms 刷新。
     *
     * 必须是**同一个** Runnable：之前每次 showPanel 都 new 一个，而刷新里又会因为会话变化
     * 调 showPanel，于是 1 个变 2 个、2 个变 4 个……每个都在做一次全量反射解析，面板开着
     * 越久越卡。现在复用一个，每次重建前先 removeCallbacks。
     */
    private val mPanelRefresh = object : Runnable {
        override fun run() {
            val dialog = mDialog ?: return
            if (!dialog.isShowing) return
            val activity = currentPanelActivity() ?: run { closeDialog(); return }
            val freshKey = KuchiguseCore.getCurrentPeerUid()
            if (freshKey != null && freshKey != mCurrentSessionKey) {
                mCurrentSessionKey = freshKey
                refreshBallColor()
                showPanel(activity)
                return
            }
            mMainHandler.postDelayed(this, PANEL_REFRESH_MS)
        }
    }

    private const val PANEL_REFRESH_MS = 500L

    // 显示延迟（300ms，避免页面切换时闪烁）
    private val mShowRunnable = object : Runnable {
        override fun run() {
            mShowingDelayed = false
            doShow()
        }
    }
    @Volatile private var mShowingDelayed = false
    @Volatile private var mShowContext: Context? = null

    /**
     * Hook 侧调用：聊天 UI 出现（草稿 VM 构造）。
     *
     * **光有草稿 VM 构造不代表人在聊天里** —— 会话列表、联系人页也会构造一个，
     * 之前就是这里把气泡在退出会话后又挂了起来，用户在会话列表点开面板就看到
     * 「未进入会话」。所以这里只在**确实有会话**时才显示气泡；会话还没捕获到
     * （AIO 创建比草稿 VM 晚一百来毫秒）就先等着，[onSessionKeyChanged] 会补上。
     */
    fun onChatShown(context: Context, sessionKey: String? = null) {
        mTempHidden = false
        if (!sessionKey.isNullOrBlank()) mCurrentSessionKey = sessionKey
        mShowContext = context
        mChatAlive = true
        if (!sessionKey.isNullOrBlank()) {
            mChatVisible = true
            postDelayedShow()
        }
    }

    private fun postDelayedShow() {
        mMainHandler.removeCallbacks(mShowRunnable)
        mMainHandler.postDelayed(mShowRunnable, 300)
        mShowingDelayed = true
    }

    private fun doShow() {
        mChatVisible = true
        mShowContext?.let { attachIfNeeded(it) }
    }

    /** Hook 侧调用：聊天 UI 隐藏（onStop/onPause 等），立即移除图标。不清 mChatAlive，后台前台往返可恢复。 */
    fun onChatHidden() {
        mChatVisible = false
        mMainHandler.removeCallbacks(mShowRunnable)
        mShowingDelayed = false
        mMainHandler.post { detach() }
    }

    /**
     * Hook 侧调用：会话栈空了（真正退出会话）。清 key，后台前台往返不再恢复。
     *
     * key 必须一起清：它就是「当前有会话」的判据，留着会让气泡在会话列表上复活。
     */
    fun onChatDestroyed() {
        mChatAlive = false
        mChatVisible = false
        mCurrentSessionKey = null
        mMainHandler.removeCallbacks(mShowRunnable)
        mShowingDelayed = false
        mMainHandler.post { detach() }
    }

    /**
     * QQ 主 Activity onResume：只在**确实还有会话**时把气泡挂回去。
     *
     * 原来判的是 mChatAlive（草稿 VM 还活着），而 onChatHidden 是故意不清它的 —— 结果
     * 「聊天 → 返回 → 退到桌面 → 再回 QQ」会把气泡挂在会话列表上。现在判 key。
     */
    fun onAppResumed(context: Context) {
        if (mCurrentSessionKey.isNullOrBlank()) return
        mChatAlive = true
        mChatVisible = true
        mMainHandler.removeCallbacks(mShowRunnable)
        mMainHandler.post { attachIfNeeded(context) }
    }

    /**
     * 会话栈顶变了：换 key、刷新球颜色，面板开着的话让它自己重建。
     *
     * 会话被捕获到就意味着人确实在聊天页里，所以这里也是气泡显示的时机 —— 气泡的
     * 显隐完全跟着会话走，不再跟着草稿 VM 走。
     */
    fun onSessionKeyChanged(key: String) {
        if (key.isBlank()) return
        val changed = key != mCurrentSessionKey
        mCurrentSessionKey = key
        if (!mChatVisible) {
            mChatVisible = true
            mChatAlive = true
            // 这里**不能**去拿当前 Activity：这条回调可能来自 AIO setter（QQ 的工作
            // 线程），而 ActivityThread.mActivities 只能在主线程读。mShowContext 留空，
            // attachIfNeeded 在主线程上自己解析。
            postDelayedShow()
        }
        if (changed) {
            mMainHandler.post {
                refreshBallColor()
                mSessionKeyCallback?.let { it(key) }
            }
        }
    }

    /**
     * 只换颜色，不动 WindowManager 布局。会话切换或面板里拨了开关之后调用，
     * 让球立刻反映「这一会话到底加不加工具癖」。
     */
    private fun refreshBallColor() {
        val icon = mIcon ?: return
        val activity = mAttachedActivity ?: return
        if (!icon.isAttachedToWindow) return
        try {
            val cfg = readConfig(activity)
            icon.setImageDrawable(roundIconDrawable(cfg.floatAlpha, cfg.enabledForSession(mCurrentSessionKey, currentIsGroup())))
        } catch (t: Throwable) { Log.e(TAG, "refreshBallColor failed", t) }
    }

    /** 任意宿主 Activity resume：若气泡在该 Activity 则刷新外观；否则重新附着。 */
    fun onHostActivityResumed(activity: Activity) {
        mMainHandler.post {
            val icon = mIcon
            if (icon != null && icon.isAttachedToWindow && mAttachedActivity === activity) {
                refreshAppearance(activity)
            } else if (mChatVisible) {
                attachIfNeeded(activity, activity)
            }
        }
    }

    private fun attachIfNeeded(context: Context, resumed: Activity? = null) {
        if (!mChatVisible) { Log.i(TAG, "attachIfNeeded: chat not visible"); return }
        val activity = resumed ?: currentActivity() ?: return
        if (mIcon != null && mIcon?.isAttachedToWindow == true && mAttachedActivity === activity) {
            refreshAppearance(activity); return
        }
        if (mIcon != null) detach()
        attachTo(activity)
    }

    /** 重新应用 floatSizeDp / floatAlpha / floatEnabled，设置修改即时生效。 */
    private fun refreshAppearance(activity: Activity) {
        val icon = mIcon ?: return
        val wm = mWindowManager ?: return
        val lp = mWindowParams ?: return
        val cfg = readConfig(activity)
        if (!cfg.floatEnabled) { detach(); return }
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        try {
            icon.setImageDrawable(roundIconDrawable(cfg.floatAlpha, cfg.enabledForSession(mCurrentSessionKey, currentIsGroup())))
            val side = dp(cfg.floatSizeDp)
            if (lp.width != side || lp.height != side) {
                lp.width = side; lp.height = side; wm.updateViewLayout(icon, lp)
            }
        } catch (t: Throwable) { Log.e(TAG, "refreshAppearance failed", t) }
    }

        /**
     * 状态栏下沿的屏幕坐标。
     *
     * 悬浮窗是 `TYPE_APPLICATION_PANEL` + `Gravity.TOP`，坐标系是整块屏幕，`y = 0` 就是状态栏
     * 本身；原来只把 y 夹在 `0..屏高-球高`，球能拖到状态栏上压住时钟和信号。
     *
     * 先问窗口自己的 inset（挖孔屏和横屏只有它准），QQ 的 Activity 是 edge-to-edge 偶尔报 0，
     * 这时退回 `status_bar_height` 尺寸资源。
     */
    private fun statusBarTop(activity: Activity): Int {
        val rootInsets = runCatching { activity.window?.decorView?.rootWindowInsets }.getOrNull()
        val inset = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                rootInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            } else {
                @Suppress("DEPRECATION")
                rootInsets?.systemWindowInsetTop ?: 0
            }
        }.getOrDefault(0)
        if (inset > 0) return inset
        return runCatching {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) activity.resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
    }

    /**
     * 把球的 y 夹在「状态栏下沿」和「屏幕底边」之间。
     *
     * `coerceIn` 在 min > max 时会抛 IllegalArgumentException，所以下界一定先兜底。
     */
    private fun clampBallY(activity: Activity, y: Int, ballHeight: Int): Int {
        val metrics = activity.resources.displayMetrics
        val top = statusBarTop(activity)
        val bottom = (metrics.heightPixels - ballHeight).coerceAtLeast(top)
        return y.coerceIn(top, bottom)
    }

    private fun attachTo(activity: Activity) {
        if (mIcon != null) detach()
        val cfg = readConfig(activity)
        if (!cfg.floatEnabled) { Log.i(TAG, "attachTo: floatEnabled=false"); return }

        val wm = activity.windowManager ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screenW = activity.resources.displayMetrics.widthPixels
        val topInset = statusBarTop(activity)
        val ballSize = dp(cfg.floatSizeDp)

        val icon = ImageView(activity).apply {
            val cfg = readConfig(activity)
            setImageDrawable(roundIconDrawable(cfg.floatAlpha, cfg.enabledForSession(mCurrentSessionKey, currentIsGroup())))
            setOnTouchListener { _, event -> onIconTouch(event) }
            setOnClickListener { showPanel(activity) }
            adjustViewBounds = false
            setScaleType(ImageView.ScaleType.FIT_XY)
        }

        try {
            val params = WindowManager.LayoutParams(
                dp(cfg.floatSizeDp), dp(cfg.floatSizeDp),
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val saved = loadPosition(activity)
                if (saved != null) {
                    x = saved.first.coerceIn(0, (screenW - ballSize).coerceAtLeast(0))
                    y = clampBallY(activity, saved.second, ballSize)
                } else {
                    x = screenW - dp(cfg.floatSizeDp + 16)
                    y = topInset + dp(110)
                }
            }
            wm.addView(icon, params)
            mWindowParams = params; mWindowManager = wm; mIcon = icon; mAttachedActivity = activity
            Log.i(TAG, "attachTo: icon added to ${activity.javaClass.name}")
        } catch (t: Throwable) { Log.e(TAG, "attachTo: addView failed", t) }
    }

    /** 触摸：raw delta + updateViewLayout。返回 false 让点击事件继续传给 OnClickListener。 */
    private fun onIconTouch(event: MotionEvent): Boolean = try {
        val lp = mWindowParams ?: return false
        val wm = mWindowManager ?: return false
        val icon = mIcon ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { mLastX = event.rawX.toInt(); mLastY = event.rawY.toInt(); mAttachedActivity?.let { refreshAppearance(it) } }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX.toInt() - mLastX; val dy = event.rawY.toInt() - mLastY
                mLastX = event.rawX.toInt(); mLastY = event.rawY.toInt()
                lp.x += dx
                // 拖动过程中就夹住，不然球会跟着手指跑到状态栏上面，松手才弹回来
                lp.y = mAttachedActivity?.let { clampBallY(it, lp.y + dy, lp.height) } ?: (lp.y + dy)
                wm.updateViewLayout(icon, lp)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mAttachedActivity?.let { ctx ->
                    val metrics = ctx.resources.displayMetrics
                    val x = lp.x.coerceIn(0, (metrics.widthPixels - lp.width).coerceAtLeast(0))
                    val y = clampBallY(ctx, lp.y, lp.height)
                    lp.x = x; lp.y = y; savePosition(ctx, x, y)
                }
            }
        }
        false
    } catch (t: Throwable) { Log.e(TAG, "onIconTouch failed", t); false }

    fun detach() {
        mMainHandler.removeCallbacks(mPanelRefresh)
        mWindowManager?.let { wm ->
            mIcon?.let { icon ->
                // 视图可能已经不在窗口上了（Activity 先一步销毁、或窗口被系统收走），
                // 这时 removeViewImmediate 抛 IllegalArgumentException。它跑在 QQ 进程里，
                // 崩的是 QQ，所以这里必须吞掉。
                try {
                    wm.removeViewImmediate(icon)
                } catch (_: Throwable) {
                    // 已经不在窗口上了，接着往下清引用就行。
                }
            }
        }
        mIcon = null; mWindowManager = null; mWindowParams = null; mAttachedActivity = null
        closeDialog()
    }

    fun dismiss() { mMainHandler.post { detach() } }

    private fun closeDialog() {
        mMainHandler.removeCallbacks(mPanelRefresh)
        mDialog?.let { d -> try { d.dismiss() } catch (_: Throwable) {} }
        mDialog = null
        mSessionKeyCallback = null
    }

    /**
     * 面板宿主 Activity。
     *
     * 弱引用：之前闭包（刷新任务、key 回调）直接强引用 Activity，会话变化时在已经销毁的
     * Activity 上 dialog.show() 就是 BadTokenException，而它跑在 Handler 里 = 崩 QQ。
     */
    @Volatile
    private var mPanelActivityRef: WeakReference<Activity>? = null

    /** 解析一个还能用的宿主 Activity；拿不到就把面板关掉。 */
    private fun currentPanelActivity(): Activity? =
        mPanelActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: currentActivity()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun showPanel(context: Context) {
        val activity = context as? Activity ?: currentPanelActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.i(TAG, "showPanel: no usable host activity, skip")
            closeDialog()
            return
        }
        // 先关旧面板（会顺带清掉 mPanelActivityRef），再挂新的引用。
        closeDialog()
        mPanelActivityRef = WeakReference(activity)
        val sessionKey = KuchiguseCore.getCurrentPeerUid()
        Log.i(TAG, "showPanel: sessionKey=$sessionKey depth=${SessionTracker.depth} closed=${SessionTracker.sessionClosed}")
        val cfg = readConfig(activity)

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val panelView = buildPanel(activity, cfg, sessionKey)
        mSessionKeyCallback = { key ->
            mMainHandler.post {
                val host = currentPanelActivity() ?: return@post
                // 只重建，不调 dismiss()：dismiss() 会 post 一个 detach()，那个 detach
                // 在重建之后才跑，会把刚建好的面板又关掉。
                showPanel(host)
            }
        }
        dialog.setContentView(panelView)
        dialog.setOnDismissListener {
            mDialog = null
            mSessionKeyCallback = null
            mPanelActivityRef = null
        }
        mDialog = dialog
        mMainHandler.removeCallbacks(mPanelRefresh)
        mMainHandler.postDelayed(mPanelRefresh, PANEL_REFRESH_MS)
        try {
            dialog.show()
        } catch (t: Throwable) {
            Log.e(TAG, "showPanel failed", t)
            closeDialog()
        }
    }

    private fun buildPanel(
        context: Context, cfg: KuchiguseConfig, sessionKey: String?,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val isNight = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isNight) 0xFF1F2124.toInt() else 0xFFF7F8FA.toInt()
        val borderColor = if (isNight) 0xFF33363B.toInt() else 0xFFE8EAEF.toInt()
        val titleColor = if (isNight) 0xFFF2F3F5.toInt() else 0xFF1A1A1A.toInt()
        val textColor = if (isNight) 0xFFE6E7E9.toInt() else 0xFF212121.toInt()
        val hintColor = if (isNight) 0xFF9AA0A8.toInt() else 0xFF9AA0A8.toInt()
        val rowBg = if (isNight) 0xFF2A2D31.toInt() else 0xFFFFFFFF.toInt()
        val onColor = 0xFF34C759.toInt(); val offColor = 0xFFFF3B30.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(bgColor); setStroke(dp(1), borderColor) })
            elevation = dp(12).toFloat()
        }
        root.minimumWidth = dp(280)

        // 标题行：左边标题，右边一个小齿轮。齿轮用来打开完整设置页 —— 从 QQ 进程
        // 里拉起，配置因此读写的是 QQ 自己的目录，不需要任何权限。
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        // hideSessionNumber 只藏标题里的号码：好友 QQ 号和群号一样都不显示。
        // key 本身照常传下去给开关和名单用，所以判定不受影响，名单里写的还是号码。
        val showKey = sessionKey != null && !cfg.hideSessionNumber
        val title = TextView(context).apply { text = if (showKey) "Kuchiguse · $sessionKey" else "Kuchiguse"; setTextColor(titleColor); textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        titleRow.addView(title)
        titleRow.addView(buildGearButton(context, titleColor, density))
        root.addView(titleRow)

        fun toast(msg: String) { try { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }

        fun sectionLabel(label: String) = TextView(context).apply { setText(label); setTextColor(hintColor); textSize = 12f; setPadding(dp(14), dp(8), dp(14), dp(2)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }

    /**
     * 会话开关的标题跟着「当前会话属于哪一类 + 那一类生效哪份名单」走，比如在群聊里、
     * 群聊白名单生效时就叫「加入群聊白名单」。开关自己在讲它动的是哪一份名单。
     */
    fun sessionSwitchLabel(c: KuchiguseConfig, isGroup: Boolean): String {
        val which = if (isGroup) "群聊" else "单聊"
        val kind = if (c.listModeFor(isGroup) == ListMode.WHITELIST) "白名单" else "黑名单"
        return "加入$which$kind"
    }

    /**
     * 会话开关的说明行：这个开关只管「在不在生效的那份名单里」，顺带说清两个位置分别
     * 会让本会话怎样，所以用户拨之前就知道结果，不用自己去推。
     */
    fun sessionHint(c: KuchiguseConfig, key: String?, isGroup: Boolean): String {
        if (key == null) return "尚未进入会话"
        val which = if (isGroup) "群聊" else "单聊"
        return if (c.listModeFor(isGroup) == ListMode.WHITELIST) {
            if (c.sessionListed(key, isGroup)) "在${which}白名单里：生效"
            else "不在${which}白名单里：不生效"
        } else {
            if (c.sessionListed(key, isGroup)) "在${which}黑名单里：不生效"
            else "不在${which}黑名单里：生效"
        }
    }

        fun toggleRow(label: String, hint: String?, initial: Boolean, onChange: (Boolean) -> Boolean): View {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(12), dp(10)); setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(rowBg) }); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val tv = TextView(context).apply { text = label; setTextColor(textColor); textSize = 15f }
            val sw = Switch(context).apply { isChecked = initial; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)) }
            val thumbSize = dp(24); val trackW = dp(48); val trackH = dp(28)
            fun thumbDrawable(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setSize(thumbSize, thumbSize) }
            fun trackDrawable(c: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor((c and 0x00FFFFFF) or (0x66000000.toInt())); setSize(trackW, trackH) }
            sw.setThumbDrawable(android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_checked), thumbDrawable(onColor)); addState(intArrayOf(), thumbDrawable(offColor)) })
            sw.setTrackDrawable(android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_checked), trackDrawable(onColor)); addState(intArrayOf(), trackDrawable(offColor)) })
            var reverting = false
            sw.setOnCheckedChangeListener { _, checked -> if (reverting) return@setOnCheckedChangeListener; if (!onChange(checked)) { reverting = true; sw.isChecked = initial; reverting = false } }
            col.addView(tv)
            if (hint != null) col.addView(TextView(context).apply { text = hint; setTextColor(hintColor); textSize = 12f })
            row.addView(col); row.addView(sw); return row
        }

        root.addView(sectionLabel("全局"))
        root.addView(toggleRow("模块总开关", "关闭后所有会话都停用，配置不会被改动", cfg.enabled) { checked ->
            val ok = setFieldLocal(context, "enabled", checked)
            if (ok) refreshBallColor() else toast("写入失败"); ok
        })
        root.addView(sectionLabel("当前会话"))
        val isGroup = currentIsGroup()
        val sessionRow = when (sessionKey) {
            null -> TextView(context).apply {
                text = "${sessionSwitchLabel(cfg, isGroup)}\n（请先进入会话）"
                setTextColor(hintColor); textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(14), dp(20), dp(14), dp(20))
                setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(rowBg) })
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            else -> toggleRow(sessionSwitchLabel(cfg, isGroup), sessionHint(cfg, sessionKey, isGroup), cfg.sessionListed(sessionKey, isGroup)) { checked ->
                val ok = setSessionLocal(context, sessionKey, isGroup, checked)
                if (ok) refreshBallColor() else toast("写入失败"); ok
            }
        }
        root.addView(sessionRow)

        // 暂时隐藏按钮。橙色，不按 mTempHidden 分色：隐藏时球已 detach，这个按钮压根没人
        // 看得见，那个「显示悬浮球」的绿色分支是死代码。
        val tempHideBtn = Button(context).apply { text = if (mTempHidden) "显示悬浮球" else "暂时隐藏悬浮球"; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(0xFFFF9500.toInt()) }); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
        tempHideBtn.setOnClickListener { mTempHidden = !mTempHidden; tempHideBtn.text = if (mTempHidden) "显示悬浮球" else "暂时隐藏悬浮球"; tempHideBtn.setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(0xFFFF9500.toInt()) }); if (mTempHidden) detach() else attachIfNeeded(context) }
        root.addView(tempHideBtn)

        // 「立刻重载配置」不在这里了：它只会改 QQ 进程内的缓存，而缓存本来就是同一个
        // 进程里设置页读的那份，放在设置页里说清楚「会丢弃未保存的改动」更合适，
        // 悬浮面板是随手点的，不该承担一个会丢改动的操作。
        return root
    }

    /**
     * 标题右侧的小齿轮，点开完整设置页。
     *
     * 从 QQ 进程内拉起 SettingsActivity，所以它读写的配置就是 QQ 自己目录里那一份，
     * 不需要任何权限，也不会再出现「设置页显示的值和 QQ 实际在用的不是一份」。
     */
    private fun buildGearButton(
        context: Context,
        color: Int,
        density: Float,
    ): View = TextView(context).apply {
        fun dp(v: Int) = (v * density).toInt()
        text = "⚙"
        setTextColor(color)
        textSize = 19f
        gravity = Gravity.CENTER
        // 触控目标不能太小：图标 22dp 视觉上够小，但可点区域给到 40dp，符合无障碍最小尺寸。
        setPadding(dp(9), dp(9), dp(9), dp(9))
        contentDescription = "完整设置"
        setOnClickListener {
            val host = currentPanelActivity()
            try {
                // 先收面板：它是 window 级浮层，不收会盖在新设置页上面。
                closeDialog()
                val ctx = host
                if (ctx == null) {
                    Log.w(TAG, "open settings: no host activity to start from")
                    return@setOnClickListener
                }
                Log.i(TAG, "open settings: starting SettingsActivity from ${ctx.javaClass.name}")
                // 必须显式写模块包名：`Intent(ctx, SettingsActivity::class.java)` 给的是
                // 相对类名，系统会拿当前包（QQ）去解析，解析不到就 START_ABORTED
                // （logcat: aInfo is null / result code=-92），表现就是点了完全没反应。
                // 包名写 QQ、类名写我们：这样 ActProxy 的 getActivityInfo 代理会被命中，
                // 返回伪造的 ActivityInfo，系统才认得这个组件。写模块包名则查不到，
                // MIUI 也会当成跨包启动拦掉。
                ctx.startActivity(
                    android.content.Intent()
                        .setClassName(
                            ConfigBridge.HOST_PACKAGE,
                            SettingsActivity::class.java.name,
                        )
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "open settings failed", t)
                try {
                    android.widget.Toast.makeText(context, "打不开设置页", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun roundIconDrawable(alpha: Float, active: Boolean): android.graphics.drawable.Drawable {
        // 只换色相，不换不透明度：红绿都保持 floatAlpha 的玻璃感，否则一切到
        // 红色就变成实心圆，看起来像换了另一个控件。
        val color: Long = if (active) 0xFF34C759L else 0xFFFF3B30L
        val a = (255 * alpha.coerceIn(0f, 1f)).toInt() shl 24
        val circleColor = ((color and 0x00FFFFFFL) or a.toLong()).toInt()
        return GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circleColor) }
    }

    /** 反射 ActivityThread.mActivities 获取当前顶层未暂停 Activity。 */
    private fun currentActivity(): Activity? = Reflect.currentActivity()

    /** 当前生效配置：进程启动时缓存的那份，不做磁盘 IO。 */
    /**
     * 面板里读配置。
     *
     * 走 [SessionKeys.effective]：号码化之前存进名单的老 uid 条目在这里换成号码，
     * 面板的开关状态才和实际判定一致。面板改开关写回时也会把迁移后的值存盘，
     * 迁移就此完成。
     */
    private fun readConfig(context: Context): KuchiguseConfig =
        SessionKeys.effective(ConfigBridge.current(context))

    /** 面板里用户显式改开关：写回同一份配置文件并刷新缓存。 */
    private fun writeConfig(context: Context, cfg: KuchiguseConfig): Boolean =
        ConfigBridge.writeFromHost(context, cfg)

    private fun setFieldLocal(context: Context, field: String, enabled: Boolean): Boolean {
        val cfg = readConfig(context)
        val saved = when (field) {
            "enabled" -> cfg.copy(enabled = enabled)
            "ignore_url" -> cfg.copy(ignoreUrl = enabled)
            "ignore_emoji" -> cfg.copy(ignoreEmoji = enabled)
            "ignore_random" -> cfg.copy(ignoreRandomLike = enabled)
            "float_enabled" -> cfg.copy(floatEnabled = enabled)
            else -> return false
        }
        return writeConfig(context, saved)
    }

    private fun setSessionLocal(context: Context, session: String, isGroup: Boolean, listed: Boolean): Boolean {
        if (session.isBlank()) return false
        return writeConfig(context, readConfig(context).withSessionListed(session, isGroup, listed))
    }

    /** 悬浮球位置：存在 QQ 自己的 external files dir，属于宿主状态，不进配置文件。 */
    private fun positionFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, "qqkuchiguse"), "position.json") }

    private fun loadPosition(context: Context): Pair<Int, Int>? {
        return try {
            val file = positionFile(context) ?: return null
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            obj.getInt("x") to obj.getInt("y")
        } catch (_: Throwable) {
            null
        }
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        try {
            val file = positionFile(context) ?: return
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().apply { put("x", x); put("y", y) }.toString())
        } catch (_: Throwable) {}
    }
}