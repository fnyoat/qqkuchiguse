import java.util.Properties

// versionCode 手动维护，每次发版 +1。
//
// 原来是 `git rev-list --count HEAD`（提交数），看着省事，实际有个会把人卡死的性质：
// 它是**历史长度的函数**，于是重写历史就让它变小。本项目重写过 dev/main 的历史来清掉
// 误提交的签名文件 —— 重写之后提交数比手机上已装的旧包还小，Android 直接拒绝覆盖安装，
// 而重写历史恰恰是必须做的事（签名 key 泄了就等于谁都能签出「同一个」模块进 LSPosed）。
//
// 提交数这个信息没丢，它在 versionName 里。versionCode 只需要「单调递增」这一个性质，
// 而历史长度不保证。
val VERSION_CODE = 100

// 只给 versionName 用的提交数：这里要它只是为了「这个包是哪次提交构建的」可追溯。
// 取不到（无 .git 的源码包、浅克隆）就退化成 0，不影响 versionCode。
fun gitCommitCount(): Int {
    return try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }
}

// 分支名要塞进 versionName，让人一眼看出这个包是从哪条线构建的。
// CI 是 detached HEAD，`git rev-parse --abbrev-ref HEAD` 在那里只会返回 "HEAD"，所以优先
// 用 GitHub 注入的 GITHUB_REF_NAME；拿不到再退回 git，最后兜一个 unknown。
// 分支名里的 `/` 换成 `-`：versionName 会进 APK 清单，带斜杠不合法。
fun gitBranch(): String {
    val fromEnv = System.getenv("GITHUB_REF_NAME")?.trim()
    val raw = when {
        !fromEnv.isNullOrEmpty() -> fromEnv
        else -> try {
            val proc = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out
        } catch (e: Exception) {
            ""
        }
    }
    val name = raw.ifEmpty { "unknown" }.removePrefix("refs/heads/")
    if (name == "HEAD") return "detached"
    return name.replace(Regex("[^A-Za-z0-9._-]"), "-")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.fnyoat.qqkuchiguse"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.fnyoat.qqkuchiguse"
        minSdk = 23
        targetSdk = 35
        versionCode = VERSION_CODE
        // versionName 必须保持干净（只含数字和点），不能带空格、括号或分支名。
        // LSPosed 模块索引（Xposed-Modules-Repo）会用清单里的 versionCode-versionName
        // 拼出对外的 release tag，形如 100-1.0；任何额外字符都会让它建不出 tag，
        // 表现为 release 被 bot 反复打成 draft。
        // 想追溯构建来源，gitBranch() / gitCommitCount() 保留在下面供 BuildConfig 调试用。
        // About 面板会把 versionName 和 versionCode 一起显示出来。
        versionName = "1.0"
    }

    // BuildConfig 里要带 versionName：关于页在 QQ 进程里读不到模块的 PackageInfo
    //（QQ 的 PackageManager 查不到本模块，HMA 一类隐藏应用的工具还会主动拦掉），
    // 版本号只能靠构建期写死的常量，见 SettingsActivity.versionLine()。
    buildFeatures {
        buildConfig = true
    }

    // 两个风味各自产出独立 APK，共用全部源码，差异只在 minSdk：
    //  - legacy：minSdk 23，兼容 Android 7 的老机型
    //  - modern：minSdk 26，面向 Android 8+ 新机型
    //
    // 两者入口都是 assets/xposed_init 声明的经典 Xposed 入口类，模块 API 依赖
    // 只在编译期提供，运行时由框架注入，不打进 APK。
    flavorDimensions += "variant"
    productFlavors {
        // 不加 versionNameSuffix：清单里的 versionName 会原样进 LSPosed 索引拼出的
        // release tag（格式 <versionCode>-<versionName>），后缀会让 tag 变成
        // "100-1.0 legacy" 这种带空格的名字。哪个 flavor 看 minSdk 就行。
        create("legacy") {
            dimension = "variant"
        }
        create("modern") {
            dimension = "variant"
            minSdk = 26
        }
    }

    signingConfigs {
        // CI 会生成 signing.properties（不进仓库）；本地未配置则用 debug 签名。
        val sp = rootProject.file("signing.properties")
        if (sp.exists()) {
            val props = Properties().apply {
                sp.inputStream().use { load(it) }
            }
            create("ci") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val sc = signingConfigs.findByName("ci")
            signingConfig = sc ?: signingConfigs.getByName("debug")
            // release 不跑 lintVital：它对两个 flavor 各占 ~15s，而 CI 里
            // 真正会 fatal 的只有 R8/missing class，已经由构建本身兜住。
            lint { checkReleaseBuilds = false }
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // 共享：单元测试
    testImplementation("junit:junit:4.13.2")

    // legacy 风味：经典 Xposed 模块 API（仅编译期，运行时由框架提供）。
    // 官方坐标 de.robv.android.xposed:api:82（JCenter/Bintray 已下线，经官方 gh-pages Maven 布局获取）。
    add("legacyCompileOnly", "de.robv.android.xposed:api:82")

    // modern 风味：官方 libxposed API（仅编译期，运行时由 LSPosed 提供）。
    // compileSdk 36 满足其 aar-metadata 的 minCompileSdk=36；AAR vendored 进
    // app/libs，构建不依赖 Maven Central 可用性，版本也与 module.prop 的
    // targetApiVersion 对齐。
    //
    // 刻意不引入 libxposed:service —— 它只提供 XRemotePreferences/Provider，
    // 本模块的配置走 QQ 目录下的 JSON 文件；而且该 AAR 引用的 AIDL 类
    // IXposedService 并未随包发布，会让 R8 报 Missing class。
    add("modernCompileOnly", files("libs/libxposed-api-101.0.0.aar"))
}