package com.hujiayucc.hook.hooker.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewParent
import java.util.Collections
import java.util.WeakHashMap

/**
 * 只处理已经命中特征类的关闭控件。默认不做坐标注入，也不扫描普通 ImageView。
 */
internal object AdCloseController {
    private const val CLICK_COOLDOWN_MS = 1_800L
    private const val VERIFY_DELAY_MS = 450L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastClickAt = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val pending = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    fun schedule(view: View, reason: String, delayMs: Long = 180L) {
        synchronized(pending) {
            if (!pending.add(view)) return
        }
        mainHandler.postDelayed({ attempt(view, reason, delayMs, 0) }, delayMs.coerceIn(100L, 600L))
    }

    private fun attempt(view: View, reason: String, delayMs: Long, retry: Int) {
        if (!isEligible(view)) {
            if (retry < 5) {
                mainHandler.postDelayed({ attempt(view, reason, delayMs, retry + 1) }, 180L)
            } else {
                pending.remove(view)
            }
            return
        }
        try {
            clickWithParentFallback(view, reason)
        } finally {
            pending.remove(view)
        }
    }

    private fun clickWithParentFallback(view: View, reason: String) {
        if (!isEligible(view)) return
        val now = SystemClock.uptimeMillis()
        val previous = lastClickAt[view]
        if (previous != null && now - previous < CLICK_COOLDOWN_MS) return

        if (view.performClick()) {
            lastClickAt[view] = now
            HookerLogger.hookDebug(
                "[AdHook] stage=click target=${describe(view)} reason=$reason result=true"
            )
            verify(view, reason)
            return
        }

        var parent: ViewParent? = view.parent
        repeat(2) { level ->
            val parentView = parent as? View ?: return@repeat
            if (isEligible(parentView) && parentView.performClick()) {
                lastClickAt[parentView] = now
                HookerLogger.hookDebug(
                    "[AdHook] stage=click target=${describe(parentView)} reason=$reason parentLevel=${level + 1} result=true"
                )
                verify(parentView, reason)
                return
            }
            parent = parentView.parent
        }
        HookerLogger.hookDebug(
            "[AdHook] stage=click target=${describe(view)} reason=$reason result=false recover=skip-coordinate"
        )
    }

    private fun verify(view: View, reason: String) {
        mainHandler.postDelayed({
            HookerLogger.hookDebug(
                "[AdHook] stage=verify target=${describe(view)} reason=$reason " +
                    "shown=${runCatching { view.isShown }.getOrDefault(false)}"
            )
        }, VERIFY_DELAY_MS)
    }

    private fun isVisibleAttached(view: View): Boolean {
        return runCatching {
            view.isShown && view.visibility == View.VISIBLE && view.isEnabled && view.isAttachedToWindow
        }.getOrDefault(false)
    }

    private fun isEligible(view: View): Boolean {
        return isVisibleAttached(view) && runCatching {
            view.isClickable || view.hasOnClickListeners()
        }.getOrDefault(false)
    }

    private fun describe(view: View): String {
        val resource = runCatching {
            if (view.id == View.NO_ID || view.id == 0) "none"
            else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("unknown")
        return "${view.javaClass.name}(id=$resource)"
    }
}
