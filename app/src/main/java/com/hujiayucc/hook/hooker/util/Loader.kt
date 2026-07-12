package com.hujiayucc.hook.hooker.util

import io.github.libxposed.api.XposedModuleInterface

object Loader: Hooker() {
    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() {
        // Author registration/payment check removed - this is a free open-source project
    }
}
