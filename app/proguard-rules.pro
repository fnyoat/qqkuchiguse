# Xposed 入口类由框架按**类名**反射实例化
# （legacy: assets/xposed_init；modern: META-INF/xposed/java_init.list），
# 既不能被重命名也不能被裁掉，否则运行期 ClassNotFoundException。
-keep class io.github.fnyoat.qqkuchiguse.KuchiguseModuleEntry { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * extends io.github.libxposed.api.XposedModule { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn de.robv.android.xposed.**
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.api.**

# 本包整体保留：QQ 侧通过反射/静态引用互相牵涉，宁可多留几 KB 也不要再出找不到类。
-keep class io.github.fnyoat.qqkuchiguse.** { *; }

# 配置文件是 JSON，字段名不能被混淆（KuchiguseConfig.toJson/fromJson 按名存取）。
-keepclassmembers class io.github.fnyoat.qqkuchiguse.config.** {
    <fields>;
    <init>(...);
}
