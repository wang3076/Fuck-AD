package com.hujiayucc.hook.hooker.sdk

import io.github.libxposed.api.XposedModuleInterface

/** 小米 Mimo / Zeus：只处理 SDK 专用跳过/关闭控件。 */
object Mimo : AdCloseSdkHooker() {
    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() {
        hookCloseView(
            "com.miui.zeus.mimo.sdk.ad.interstitial.view.InterstitialSkipCountDownView",
            "mimo-interstitial-skip"
        )
        hookCloseView(
            "com.miui.zeus.mimo.sdk.ad.reward.view.RewardSkipCountDownView",
            "mimo-reward-skip"
        )
        traceLifecycle(
            "com.miui.zeus.mimo.sdk.ad.interstitial.view.InterstitialSkipCountDownView",
            "onAttachedToWindow", "onVisibilityChanged", "onFinish", "performClick"
        )
        traceLifecycle(
            "com.miui.zeus.mimo.sdk.ad.reward.view.RewardSkipCountDownView",
            "onAttachedToWindow", "onVisibilityChanged", "onFinish", "performClick"
        )
    }
}
