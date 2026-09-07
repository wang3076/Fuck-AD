package com.hujiayucc.hook.hooker.sdk

import com.hujiayucc.hook.hooker.util.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier

abstract class SimpleSdkHooker : Hooker() {
    private val booleanTypes = setOf(Boolean::class.javaPrimitiveType, Boolean::class.java)
    private val voidTypes = setOf(Void.TYPE, Void::class.java)

    protected fun Method.replaceWithDefault() {
        // SDK 入口只做观察，保留原始调用、回调和生命周期收尾。
        hook {
            after {
                val stage = when {
                    name.contains("init", ignoreCase = true) -> "init"
                    name.contains("load", ignoreCase = true) ||
                        name.contains("request", ignoreCase = true) -> "load"
                    name.contains("show", ignoreCase = true) -> "show"
                    else -> "sdk"
                }
                logHookDebug(
                    "[AdHook] stage=$stage class=${declaringClass.name} " +
                        "method=$name args=${args.size} result=${result?.javaClass?.name ?: "null"}"
                )
            }
        }
    }

    protected fun Class<*>.hookMethods(vararg names: String) {
        (cachedDeclaredMethods().asSequence() + cachedMethods().asSequence())
            .filter { method ->
                method.name in names &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }
            .distinctBy { method -> method.toGenericString() }
            .forEach { method -> method.replaceWithDefault() }
    }

    protected fun hookClassMethods(className: String, vararg methodNames: String) {
        className.toClassOrNull()?.hookMethods(*methodNames)
    }
}
