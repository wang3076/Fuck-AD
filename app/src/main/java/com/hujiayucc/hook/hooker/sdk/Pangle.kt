package com.hujiayucc.hook.hooker.sdk

import android.view.View
import com.hujiayucc.hook.hooker.util.Hooker
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/** 穿山甲 */
object Pangle : Hooker() {
    private val booleanTypes = setOf(Boolean::class.javaPrimitiveType, Boolean::class.java)
    private val voidTypes = setOf(Void.TYPE, Void::class.java)

    private fun Method.replaceWithDefault() {
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

    private fun Class<*>.methodOrNull(name: String, descriptor: String): Method? {
        return cachedDeclaredMethods().firstOrNull { method ->
            method.name == name && method.toDescriptor() == descriptor
        }
    }

    private fun Method.toDescriptor(): String {
        return parameterTypes.joinToString(
            prefix = "(",
            postfix = ")${returnType.toDescriptor()}"
        ) { parameterType -> parameterType.toDescriptor() }
    }

    private fun Class<*>.toDescriptor(): String {
        if (isPrimitive) {
            return when (this) {
                Void.TYPE -> "V"
                Boolean::class.javaPrimitiveType -> "Z"
                Byte::class.javaPrimitiveType -> "B"
                Char::class.javaPrimitiveType -> "C"
                Short::class.javaPrimitiveType -> "S"
                Int::class.javaPrimitiveType -> "I"
                Long::class.javaPrimitiveType -> "J"
                Float::class.javaPrimitiveType -> "F"
                Double::class.javaPrimitiveType -> "D"
                else -> "V"
            }
        }
        if (isArray) return name.replace('.', '/')
        return "L${name.replace('.', '/')};"
    }

    private fun Class<*>.hookMethods(vararg names: String) {
        cachedDeclaredMethods()
            .filter { method -> method.name in names }
            .forEach { method -> method.replaceWithDefault() }
    }

    private fun hookLegacySdkGuards() {
        "com.bytedance.sdk.openadsdk.api.ln".toClassOrNull()
            ?.hookMethods("init", "start", "isInitSuccess", "isSdkReady")

        "com.bytedance.sdk.openadsdk.core.AdSdkInitializerHolder".toClassOrNull()
            ?.hookMethods("init", "start", "isInitSuccess", "isSdkReady")

        "com.bytedance.sdk.openadsdk.core.component.splash.countdown.TTCountdownViewForCircle".toClassOrNull()
            ?.traceMethods("onAttachedToWindow", "onVisibilityChanged", "setText", "performClick")

        "com.bytedance.sdk.openadsdk.core.component.splash.e.r\$1".toClassOrNull()
            ?.traceMethods("run")
    }

    private fun hookSdkInit() {
        "com.bytedance.sdk.openadsdk.TTAdSdk".toClassOrNull()
            ?.let { ttAdSdk ->
                ttAdSdk.methodOrNull("init", "(Landroid/content/Context;Lcom/bytedance/sdk/openadsdk/TTAdConfig;)Z")
                    ?.traceMethod()
                ttAdSdk.methodOrNull("start", "(Lcom/bytedance/sdk/openadsdk/TTAdSdk\$Callback;)V")
                    ?.traceMethod()
                ttAdSdk.methodOrNull("isInitSuccess", "()Z")
                    ?.traceMethod()
                ttAdSdk.methodOrNull("isSdkReady", "()Z")
                    ?.traceMethod()
                ttAdSdk.methodOrNull("updateAdConfig", "(Lcom/bytedance/sdk/openadsdk/TTAdConfig;)V")
                    ?.traceMethod()
                ttAdSdk.methodOrNull("updateConfigAuth", "(Lcom/bytedance/sdk/openadsdk/TTAdConfig;)V")
                    ?.traceMethod()
            }

        "com.bytedance.sdk.openadsdk.api.a".toClassOrNull()
            ?.let { initializer ->
                initializer.methodOrNull(
                    "a",
                    "(Landroid/content/Context;Lcom/bytedance/sdk/openadsdk/AdConfig;Lcom/bytedance/sdk/openadsdk/TTAdSdk\$InitCallback;)V"
                )?.traceMethod()
                initializer.methodOrNull(
                    "b",
                    "(Landroid/content/Context;Lcom/bytedance/sdk/openadsdk/AdConfig;Lcom/bytedance/sdk/openadsdk/TTAdSdk\$InitCallback;)Z"
                )?.traceMethod()
            }
    }

    private fun hookConfig() {
        "com.bytedance.sdk.openadsdk.CSJConfig".toClassOrNull()
            ?.let { config ->
                config.traceMethods(
                    "isPaid", "isDebug", "isAllowShowNotify", "isSupportMultiProcess",
                    "isUseMediation", "getPluginUpdateConfig", "getDirectDownloadNetworkType",
                    "getMediationConfig"
                )
            }
    }

    private fun hookAdSlot() {
        $$"com.bytedance.sdk.openadsdk.AdSlot$Builder".toClassOrNull()
            ?.let { builder ->
                listOf(
                    "setAdType",
                    "setCodeId",
                    "setAdCount",
                    "setSupportDeepLink",
                    "setPrimeRit",
                    "setRewardName",
                    "setRewardAmount",
                    "setMediationAdSlot"
                ).forEach { methodName ->
                    builder.traceMethods(methodName)
                }
            }
    }

    private fun hookAdRequests() {
        "com.bytedance.sdk.openadsdk.c.a.a\$a".toClassOrNull()
            ?.let { adNative ->
                listOf(
                    "loadSplashAd",
                    "loadFeedAd",
                    "loadStream",
                    "loadDrawFeedAd",
                    "loadNativeAd",
                    "loadNativeExpressAd",
                    "loadExpressDrawFeedAd",
                    "loadBannerExpressAd",
                    "loadRewardVideoAd",
                    "loadFullScreenVideoAd"
                ).forEach { methodName ->
                    adNative.traceMethods(methodName)
                }
            }

        "com.bytedance.sdk.openadsdk.api.a\$c".toClassOrNull()
            ?.let { manager ->
                manager.traceMethods(
                    "createAdNative", "requestPermissionIfNecessary",
                    "tryShowInstallDialogWhenExit", "getBiddingToken"
                )
            }
    }

    private fun hookSplashAd() {
        "com.bytedance.sdk.openadsdk.c.a.a.b".toClassOrNull()
            ?.let { splashAd ->
                splashAd.traceMethods(
                    "showSplashView", "showSplashClickEyeView", "showSplashCardView",
                    "startClickEye", "hideSkipButton", "getSplashView",
                    "getSplashClickEyeView", "getSplashCardView"
                )
            }
    }

    private fun hookAdObjects() {
        listOf(
            "com.bytedance.sdk.openadsdk.c.a.a.h",
            "com.bytedance.sdk.openadsdk.c.a.a.i",
            "com.bytedance.sdk.openadsdk.c.a.a.l"
        ).forEach { className ->
            className.toClassOrNull()
                ?.hookMethods(
                    "getAdView",
                    "registerViewForInteraction",
                    "render",
                    "showInteractionExpressAd",
                    "destroy"
                )
        }

        "com.bytedance.sdk.openadsdk.c.a.a.m".toClassOrNull()
            ?.hookMethods(
                "getExpressAdView",
                "render",
                "showInteractionExpressAd",
                "destroy"
            )

        "com.bytedance.sdk.openadsdk.c.a.a.j".toClassOrNull()
            ?.hookMethods("showFullScreenVideoAd")

        "com.bytedance.sdk.openadsdk.c.a.a.n".toClassOrNull()
            ?.hookMethods("showRewardVideoAd")
    }

    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() {
        hookLegacySdkGuards()
        hookSdkInit()
        hookConfig()
        hookAdSlot()
        hookAdRequests()
        hookSplashAd()
        hookAdObjects()
    }
}