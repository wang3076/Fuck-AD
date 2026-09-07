package com.hujiayucc.hook.hooker.sdk

import io.github.libxposed.api.XposedModuleInterface

/** SmartDigiMkt：按专用关闭控件类名处理，避免通用 ImageView 误触。 */
object SmartDigiMkt : AdCloseSdkHooker() {
    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() {
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.CloseImageView",
            "smartdigimkt-close-image"
        )
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.CloseFrameLayout",
            "smartdigimkt-close-frame"
        )
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.CloseHeaderView",
            "smartdigimkt-close-header"
        )
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.CountDownCloseView",
            "smartdigimkt-countdown-close"
        )
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.CountDownView",
            "smartdigimkt-countdown"
        )
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.guidetoclickv2.GTCV2InnerCountDownView",
            "smartdigimkt-gtc-countdown"
        )
        hookCloseView(
            "com.smartdigimkt.sdk.basead.ui.improveclick.incentivetask.CountDownSkipIncentiveTaskView",
            "smartdigimkt-incentive-skip"
        )
    }
}
