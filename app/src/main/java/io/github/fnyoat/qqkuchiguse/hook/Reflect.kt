/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.hook

import android.app.Activity
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * QQ 的 NT 侧类层次很浅但方法名混淆，按「整条继承链 + 去重」收集声明方法，
 * 两个风味的入口都要用，所以放在 main 里共用。
 */
object Reflect {

    /** 从 [cls] 一路往上收集 declaredMethods，按声明处去重（父类方法只算一次）。 */
    fun hierarchyMethods(cls: Class<*>): List<Method> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Method>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.isSynthetic || m.isBridge) continue
                if (!seen.add(signature(m))) continue
                out.add(m)
            }
            c = c.superclass
        }
        return out
    }

    /** 整条继承链上的构造器（含父类）。 */
    fun hierarchyConstructors(cls: Class<*>): List<Constructor<*>> {
        val out = ArrayList<Constructor<*>>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (ctor in c.declaredConstructors) {
                if (ctor.isSynthetic) continue
                out.add(ctor)
            }
            c = c.superclass
        }
        return out
    }

    /** 实例方法（跳过 static / abstract，抽象的没法 hook）。 */
    fun instanceMethods(cls: Class<*>): List<Method> = hierarchyMethods(cls).filter { m ->
        !Modifier.isStatic(m.modifiers) && !Modifier.isAbstract(m.modifiers)
    }

    private fun signature(m: Method): String = buildString {
        append(m.declaringClass.name).append('#').append(m.name).append('(')
        for (p in m.parameterTypes) append(p.name).append(',')
        append(')')
    }

    /**
     * 反射 `ActivityThread.mActivities` 取当前顶层（未暂停）Activity。
     *
     * 没有 public API 能拿到它，只能反射；这是 Android 隐藏 API 的经典写法，QQ 自己
     * 也这么用。取不到就返回 null，调用方必须容错。
     */
    fun currentActivity(): Activity? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null) ?: return null
            val mActivities = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val map = mActivities.get(at) as? Map<*, *> ?: return null
            for (record in map.values) {
                if (record == null) continue
                val rc = record.javaClass
                val paused = runCatching {
                    rc.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
                }.getOrDefault(true)
                if (paused) continue
                val activity = runCatching {
                    rc.getDeclaredField("activity").apply { isAccessible = true }.get(record)
                }.getOrNull()
                if (activity is Activity) return activity
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
