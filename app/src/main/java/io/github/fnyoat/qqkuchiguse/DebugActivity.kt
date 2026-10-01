package io.github.fnyoat.qqkuchiguse

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.fnyoat.qqkuchiguse.config.ConfigBridge

/**
 * 从桌面图标打开时显示的页面。
 *
 * 刻意不做任何配置：设置界面运行在 QQ 进程里（见 [SettingsActivity]，由 ActProxy 通过 stub
 * Intent 拉起），独立启动的进程既没有 QQ 的 ClassLoader 也没有它的 Resources，配置页在这里
 * 无论如何都打不开。所以这里只负责讲清楚「该在哪配、为什么这里不能配」。
 */
class DebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Kuchiguse)
        title = getString(R.string.debug_title)

        val pad = dp(20f)
        val body = TextView(this)
        body.text = getString(R.string.debug_body)
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        body.setLineSpacing(dp(4f).toFloat(), 1f)
        body.setTextIsSelectable(true)

        val version = TextView(this)
        version.text = buildString {
            append(getString(R.string.debug_version_line, versionName()))
            val qq = qqVersion()
            if (qq != null) {
                append('\n')
                append(getString(R.string.debug_qq_line, qq))
            }
        }
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        version.typeface = Typeface.MONOSPACE
        version.setTextColor(0xFF757575.toInt())

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.START
        root.setPadding(pad, pad, pad, pad)
        root.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            version,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24f) },
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    /**
     * 模块自己的版本，取自编译期写进 dex 的 [BuildConfig]。
     *
     * 不能查 PackageManager：这个页面在 QQ 进程里跑，`getPackageName()` 拿到的是宿主 QQ 的
     * 包名（会显示成 QQ 的版本）；点名查模块包名同样不可靠 —— 那是 QQ 的 PackageManager，
     * 模块没装或被 HMA 这类隐藏应用的工具藏起来时查询会直接失败，版本行就整行没了。
     */
    private fun versionName(): String =
        if (BuildConfig.VERSION_NAME.isEmpty()) "?" else BuildConfig.VERSION_NAME

    private fun qqVersion(): String? = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(ConfigBridge.HOST_PACKAGE, 0).versionName
    } catch (t: Throwable) {
        null
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics,
    ).toInt()
}
