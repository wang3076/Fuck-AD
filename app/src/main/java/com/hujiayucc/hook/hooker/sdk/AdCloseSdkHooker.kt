package com.hujiayucc.hook.hooker.sdk

import android.view.View
import com.hujiayucc.hook.hooker.util.AdCloseController
import com.hujiayucc.hook.hooker.util.Hooker
import io.github.libxposed.api.XposedModuleInterface

/** 只对 SDK 自带的关闭/跳过 View 做延迟点击，不改写 SDK 入口返回值。 */
abstract class AdCloseSdkHooker : Hooker() {
    protected fun hookCloseView(className: String, reason: String) {
        className.toClassOrNull()?.let { clazz ->
            clazz.cachedDeclaredMethods()
                .filter { method ->
                    method.name in setOf(
                        "onAttachedToWindow", "onVisibilityChanged", "setNumber", "setText",
                        "show", "start", "onFinish", "eo", "mA"
                    )
                }
                .forEach { method ->
                    method.hook {
                        after {
                            (instance as? View)?.let { view ->
                                AdCloseController.schedule(view, reason)
                            }
                        }
                    }
                }
        }
    }

    protected fun hookCloseViewMethods(className: String, reason: String, vararg methodNames: String) {
        className.toClassOrNull()?.let { clazz ->
            clazz.cachedDeclaredMethods()
                .filter { it.name in methodNames }
                .forEach { method ->
                    method.hook {
                        after {
                            (instance as? View)?.let { view ->
                                AdCloseController.schedule(view, reason)
                            }
                        }
                    }
                }
        }
    }

    protected fun traceLifecycle(className: String, vararg methodNames: String) {
        className.toClassOrNull()?.cachedDeclaredMethods()
            ?.filter { it.name in methodNames }
            ?.forEach { method ->
                method.hook {
                    after {
                        logHookDebug(
                            "[AdHook] stage=show class=${method.declaringClass.name} " +
                                "method=${method.name} args=${args.size}"
                        )
                    }
                }
            }
    }

    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() = Unit
}
