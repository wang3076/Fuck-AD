package com.hujiayucc.hook.hooker.sdk

import android.annotation.SuppressLint
import android.view.View
import com.hujiayucc.hook.hooker.util.Hooker
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/** 快手 */
object KW : AdCloseSdkHooker() {
    private val hookedLoadManagerClasses = Collections.synchronizedSet(mutableSetOf<String>())
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

    private fun Class<*>.hookMethods(vararg names: String) {
        (cachedDeclaredMethods().asSequence() + cachedMethods().asSequence())
            .filter { method ->
                method.name in names &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }
            .distinctBy { method -> method.toGenericString() }
            .forEach { method -> method.replaceWithDefault() }
    }

    private fun hookSdkInit() {
        "com.kwad.sdk.api.KsAdSDK".toClassOrNull()
            ?.let { sdk ->
                sdk.hookMethods(
                    "init",
                    "start",
                    "isInitSuccess",
                    "isSdkReady"
                )

                sdk.methods("getLoadManager").hook {
                    after {
                        result?.let { loadManager -> hookLoadManager(loadManager.javaClass) }
                    }
                }
            }
    }

    private fun hookLoadManager(loadManagerClass: Class<*>) {
        val loaderKey = System.identityHashCode(loadManagerClass.classLoader)
        if (!hookedLoadManagerClasses.add("${loadManagerClass.name}@$loaderKey")) return
        loadManagerClass.hookMethods(
            "loadFullScreenVideoAd",
            "loadRewardVideoAd",
            "loadFeedAd",
            "loadConfigFeedAd",
            "loadDrawAd",
            "loadNativeAd",
            "loadSplashScreenAd",
            "loadInterstitialAd",
            "loadInteractionAd"
        )
    }

    private fun hookAdObjects() {
        "com.kwad.sdk.api.KsRewardVideoAd".toClassOrNull()
            ?.hookMethods(
                "showRewardVideoAd",
                "setRewardAdInteractionListener",
                "setDownloadListener"
            )

        "com.kwad.sdk.api.KsFullScreenVideoAd".toClassOrNull()
            ?.hookMethods(
                "showFullScreenVideoAd",
                "setFullScreenVideoAdInteractionListener",
                "setDownloadListener"
            )

        "com.kwad.sdk.api.KsInterstitialAd".toClassOrNull()
            ?.hookMethods(
                "showInterstitialAd",
                "setAdInteractionListener",
                "setDownloadListener",
                "isAdEnable",
                "getECPM"
            )

        "com.kwad.sdk.api.KsSplashScreenAd".toClassOrNull()
            ?.hookMethods(
                "showSplashMiniWindow",
                "showSplashMiniWindowIfNeeded",
                "getView",
                "setSplashScreenAdInteractionListener"
            )

        "com.kwad.sdk.api.KsFeedAd".toClassOrNull()
            ?.hookMethods(
                "getFeedView",
                "setVideoSoundEnable",
                "setAdInteractionListener",
                "setDownloadListener"
            )

        "com.kwad.sdk.api.KsDrawAd".toClassOrNull()
            ?.hookMethods(
                "getDrawView",
                "setAdInteractionListener",
                "setVideoSoundEnable",
                "setDownloadListener"
            )

        "com.kwad.sdk.api.KsNativeAd".toClassOrNull()
            ?.hookMethods(
                "registerViewForInteraction",
                "setVideoPlayListener",
                "setDownloadListener",
                "isAdEnable",
                "getAdSource",
                "getAdDescription",
                "getAdView"
            )
    }

    private fun hookDedicatedCloseControls() {
        hookCloseView(
            "com.kwad.components.ad.splashscreen.widget.SkipView",
            "kwad-skip-view"
        )
        hookCloseView(
            "com.kwad.components.ad.splashscreen.widget.CircleSkipView",
            "kwad-circle-skip-view"
        )
        hookCloseView(
            "com.kwad.components.ad.splashscreen.widget.CloseCountDownView",
            "kwad-close-countdown-view"
        )
        hookCloseView(
            "com.kwad.components.core.widget.KsAutoCloseView",
            "kwad-auto-close-view"
        )
        hookCloseViewMethods(
            "com.kwad.components.ad.reward.widget.RewardPreviewTopBarView",
            "kwad-reward-topbar-close",
            "onAttachedToWindow", "onVisibilityChanged", "setImageResource", "setVisibility"
        )
    }

    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() {
        hookSdkInit()
        hookAdObjects()
        hookDedicatedCloseControls()
    }
}
