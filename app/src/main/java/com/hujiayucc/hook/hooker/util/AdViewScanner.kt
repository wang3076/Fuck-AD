package com.hujiayucc.hook.hooker.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/** 酷安等目标中的强特征广告布局处理，普通业务 ImageView 不进入此路径。 */
internal object AdViewScanner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sdkPrefixes = listOf(
        "com.bytedance.sdk.openadsdk",
        "com.kwad.",
        "com.miui.zeus.mimo.",
        "com.smartdigimkt.sdk",
        "com.qq.e.",
        "com.mbridge.msdk.",
        "com.anythink."
    )
    private val adWords = listOf(
        "ad_container", "ad_view", "ad_root", "native_ad", "feed_ad", "splash_ad",
        "advert", "ksad_", "splash_skip", "endcard", "reward_ad"
    )
    private val closeWords = listOf("close", "skip", "dismiss", "关闭", "跳过")
    private val excludedWords = listOf("download", "install", "open", "体验", "立即", "下载", "安装")
    private val scheduledRoots = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private const val ROOT_COOLDOWN_MS = 700L
    private const val MAX_NODES_PER_SCAN = 1200

    fun schedule(root: View, reason: String) {
        val canonicalRoot = runCatching { root.rootView }.getOrDefault(root)
        val now = SystemClock.uptimeMillis()
        val previous = scheduledRoots[canonicalRoot]
        if (previous != null && now - previous < ROOT_COOLDOWN_MS) return
        scheduledRoots[canonicalRoot] = now
        scheduleBurst(canonicalRoot, reason)
    }

    fun scheduleBurst(root: View, reason: String) {
        listOf(0L, 180L, 360L, 720L, 1_500L, 3_000L, 5_000L).forEach { delay ->
            mainHandler.postDelayed({ scan(root, reason) }, delay)
        }
    }

    fun scheduleActivity(activity: android.app.Activity, reason: String) {
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return
        schedule(root, reason)
    }

    fun scheduleView(view: View, reason: String) {
        val root = runCatching { view.rootView }.getOrDefault(view)
        schedule(root, reason)
    }

    fun isKnownCloseCandidate(view: View): Boolean {
        return isCloseCandidate(view, identity(view), (view as? TextView)?.text?.toString().orEmpty())
    }

    private fun scan(root: View, reason: String) {
        if (!root.isAttachedToWindow || root.visibility != View.VISIBLE) return
        val budget = intArrayOf(0)
        visit(root, reason, budget)
        HookerLogger.info(
            "[AdHook] event=layout_scan reason=$reason root=${root.javaClass.name} nodes=${budget[0]}"
        )
    }

    private fun visit(view: View, reason: String, budget: IntArray) {
        if (budget[0]++ >= MAX_NODES_PER_SCAN) return
        if (view.visibility != View.VISIBLE) return
        if (view.javaClass.name == "com.coolapk.market.view.splash.CountdownView") return

        val identity = identity(view)
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val closeCandidate = isCloseCandidate(view, identity, text)
        if (closeCandidate) {
            AdCloseController.schedule(view, "coolapk-$reason")
        } else if (isStrongAdNode(view, identity, text)) {
            view.visibility = View.GONE
            HookerLogger.hookDebug(
                "[AdHook] stage=layout target=${view.javaClass.name} id=$identity " +
                    "reason=$reason result=hidden"
            )
            return
        }

        (view as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                if (budget[0] >= MAX_NODES_PER_SCAN) break
                visit(group.getChildAt(index), reason, budget)
            }
        }
    }

    private fun isCloseCandidate(view: View, identity: String, text: String): Boolean {
        if (view.javaClass.name == "com.coolapk.market.view.splash.CountdownView") return false
        val value = "$identity ${view.javaClass.name} ${view.contentDescription?.toString().orEmpty()} $text"
            .lowercase(Locale.ROOT)
        if (excludedWords.any { value.contains(it) }) return false
        return closeWords.any { value.contains(it) } &&
            (isSdkView(view) || hasAdAncestor(view) || identity.contains("splash"))
    }

    private fun isStrongAdNode(view: View, identity: String, text: String): Boolean {
        val value = "$identity ${view.javaClass.name} $text".lowercase(Locale.ROOT)
        if (excludedWords.any { value.contains(it) }) return false
        if (isSdkView(view)) {
            return adWords.any { value.contains(it) } ||
                closeWords.any { value.contains(it) } ||
                hasAdAncestor(view)
        }
        if (adWords.any { identity.contains(it) }) return true
        return text.contains("广告") && hasAdAncestor(view)
    }

    private fun isSdkView(view: View): Boolean {
        val name = view.javaClass.name
        return sdkPrefixes.any(name::startsWith)
    }

    private fun hasAdAncestor(view: View): Boolean {
        var parent = view.parent
        repeat(3) {
            val parentView = parent as? View ?: return false
            val id = runCatching {
                if (parentView.id == View.NO_ID || parentView.id == 0) ""
                else parentView.resources.getResourceEntryName(parentView.id).lowercase(Locale.ROOT)
            }.getOrDefault("")
            val value = "$id ${parentView.javaClass.name}".lowercase(Locale.ROOT)
            if (isSdkView(parentView) || adWords.any { value.contains(it) }) return true
            parent = parentView.parent
        }
        return false
    }

    private fun identity(view: View): String {
        return runCatching {
            if (view.id == View.NO_ID || view.id == 0) "none"
            else view.resources.getResourceEntryName(view.id).lowercase(Locale.ROOT)
        }.getOrDefault("unknown")
    }
}