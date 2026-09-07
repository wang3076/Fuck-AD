package com.hujiayucc.hook.hooker.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import com.hujiayucc.hook.annotation.Run
import com.hujiayucc.hook.hooker.util.Hooker
import io.github.libxposed.api.XposedModuleInterface
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Run(
    appName = "KOOK",
    packageName = "cn.kaiheila",
    action = "开屏广告",
    versions = [
        "1.75.0",
        "1.87.2"
    ]
)
object Kook : Hooker() {
    private const val AD_ACTIVITY = "cn.kaiheila.ad.CJADActivity"
    private const val COUNTDOWN_VIEW = "cj.mobile.jt.core.ui.widget.CountdownView"
    private val skipNameTokens = listOf(
        "skip", "countdown", "count_down", "countdownview", "close"
    )
    private val excludedNameTokens = listOf(
        "hotarea", "hot_area", "webview", "template", "advertlayout", "logo", "shake",
        "download", "install", "detail", "jump", "open"
    )
    private const val WEB_PROBE_SCRIPT = """
        (function () {
          try {
             var allowed = /skip|close/i;
             var blocked = /hotarea|hot_area|webview|template|advertlayout|logo|shake|download|install|detail|jump|open/i;
            var label = function (node) {
              var attrs = ['id', 'name', 'aria-label', 'title', 'alt', 'data-testid', 'data-id'];
              var values = attrs.map(function (key) { return node.getAttribute(key) || ''; });
              return values.concat([node.textContent || '']).join(' ').replace(/\s+/g, ' ').trim();
            };
            var visible = function (node) {
              var style = window.getComputedStyle(node);
              var rect = node.getBoundingClientRect();
              return style && style.display !== 'none' && style.visibility !== 'hidden' &&
                rect.width >= 18 && rect.height >= 18;
            };
            var inCountdown = function (node) {
              for (var current = node, depth = 0; current && depth < 8; current = current.parentElement, depth++) {
                if (/countdown/i.test(label(current))) return true;
              }
              return false;
            };
            var controls = Array.prototype.slice.call(
              document.querySelectorAll('[id], [name], [aria-label], [title], [alt], [role], button, a')
            );
            var candidates = controls.filter(function (node) {
              var semantic = label(node);
              var rect = node.getBoundingClientRect();
              var role = String(node.getAttribute('role') || '').toLowerCase();
              var interactive = /^(BUTTON|A)$/i.test(node.tagName) || role === 'button' || node.tabIndex >= 0;
              return semantic && allowed.test(semantic) && !blocked.test(semantic) && interactive &&
                visible(node) && inCountdown(node) &&
                rect.top <= window.innerHeight * 0.45 && rect.right >= window.innerWidth * 0.45;
            });
             if (candidates.length) return 'candidate|' + label(candidates[0]).slice(0, 120);
             return '';
          } catch (error) {
            return 'error|' + (error && error.name ? error.name : 'unknown');
          }
        })();
    """
    private const val WEB_CLICK_SCRIPT = """
        (function () {
          try {
            var allowed = /skip|close/i;
            var blocked = /hotarea|hot_area|webview|template|advertlayout|logo|shake|download|install|detail|jump|open/i;
            var label = function (node) {
              var attrs = ['id', 'name', 'aria-label', 'title', 'alt', 'data-testid', 'data-id'];
              var values = attrs.map(function (key) { return node.getAttribute(key) || ''; });
              return values.concat([node.textContent || '']).join(' ').replace(/\s+/g, ' ').trim();
            };
            var visible = function (node) {
              var style = window.getComputedStyle(node);
              var rect = node.getBoundingClientRect();
              return style && style.display !== 'none' && style.visibility !== 'hidden' &&
                rect.width >= 18 && rect.height >= 18;
            };
            var inCountdown = function (node) {
              for (var current = node, depth = 0; current && depth < 8; current = current.parentElement, depth++) {
                if (/countdown/i.test(label(current))) return true;
              }
              return false;
            };
            var candidate = Array.prototype.slice.call(
              document.querySelectorAll('[id], [name], [aria-label], [title], [alt], [role], button, a')
            ).filter(function (node) {
              var semantic = label(node);
              var rect = node.getBoundingClientRect();
              var role = String(node.getAttribute('role') || '').toLowerCase();
              var interactive = /^(BUTTON|A)$/i.test(node.tagName) || role === 'button' || node.tabIndex >= 0;
              return semantic && allowed.test(semantic) && !blocked.test(semantic) && interactive &&
                visible(node) && inCountdown(node) &&
                rect.top <= window.innerHeight * 0.45 && rect.right >= window.innerWidth * 0.45;
            })[0];
            if (!candidate) return '';
            candidate.click();
            return 'clicked|' + label(candidate).slice(0, 120);
          } catch (error) {
            return 'error|' + (error && error.name ? error.name : 'unknown');
          }
        })();
    """
    private val pendingClicks = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )
    private val lastClickAt = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val callbacksInstalled = AtomicBoolean(false)
    private const val CLICK_COOLDOWN_MS = 1_500L
    private val scheduledActivities = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    )
    private val activeAdActivities = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    )
    private val pendingRootScans = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )
    private val pendingWebChecks = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )
    override fun XposedModuleInterface.PackageReadyParam.onPackageReady() {
        if (!isMainProcess(this)) return

        installPhase { registerAdActivityCallbacks() }
        installPhase { installViewHooks() }
        installPhase { installActivityHooks() }
    }

    private fun installPhase(block: () -> Unit) {
        runCatching(block)
    }

    private fun installViewHooks() {
        TextView::class.java.method("onDraw").hook {
            after {
                val view = instance<TextView>()
                if (isKookAdView(view) && hasNamedSkipSemantics(view)) {
                    scheduleClick(view, "text-draw")
                }
            }
        }

        View::class.java.method("onDraw").hook {
            after {
                val view = instance<View>()
                if (isKookAdView(view) && isCountdownView(view)) {
                    scheduleClick(view, "countdown-draw")
                }
            }
        }

        View::class.java.method("onAttachedToWindow").hook {
            after {
                val view = instance<View>()
                if (!isKookAdView(view)) return@after
                if (hasNamedSkipSemantics(view) || isCountdownView(view)) {
                    scheduleClick(view, "attach")
                }
                if (view is ViewGroup && view.parent !is View) {
                    scanFrom(view, "attach-root")
                }
            }
        }
        ViewGroup::class.java.methods("addView").hook {
            after {
                val added = args.firstOrNull() as? View ?: return@after
                if (!isKookAdView(added)) return@after
                if (hasNamedSkipSemantics(added) || isCountdownView(added)) {
                    scheduleClick(added, "add-view")
                }
                if (added is ViewGroup) scanFrom(added, "add-view")
            }
        }
    }

    private fun installActivityHooks() {
        Activity::class.java.method("onCreate", Bundle::class.java).hook {
            after { observeActivity(instance<Activity>(), "activity-create") }
        }
        Activity::class.java.method("onStart").hook {
            after { observeActivity(instance<Activity>(), "activity-start") }
        }
        Activity::class.java.method("onResume").hook {
            after { observeActivity(instance<Activity>(), "activity-resume") }
        }
        Activity::class.java.method(
            "onWindowFocusChanged",
            Boolean::class.javaPrimitiveType!!
        ).hook {
            after {
                if (args.firstOrNull() == true) {
                    observeActivity(instance<Activity>(), "activity-focus")
                }
            }
        }
        Instrumentation::class.java.method(
            "callActivityOnCreate",
            Activity::class.java,
            Bundle::class.java
        ).hook {
            after {
                (args.firstOrNull() as? Activity)?.let { activity ->
                    observeActivity(activity, "instrumentation-create")
                }
            }
        }
        Instrumentation::class.java.method(
            "callActivityOnResume",
            Activity::class.java
        ).hook {
            after {
                (args.firstOrNull() as? Activity)?.let { activity ->
                    observeActivity(activity, "instrumentation-resume")
                }
            }
        }
    }

    private fun observeActivity(activity: Activity, stage: String) {
        if (activity.javaClass.name == AD_ACTIVITY) {
            scheduleActivityScan(activity, stage)
        }
    }


    private fun registerAdActivityCallbacks() {
        if (!callbacksInstalled.compareAndSet(false, true)) return
        val application = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Application
        }.getOrNull()
        if (application == null) {
            callbacksInstalled.set(false)
            runMainDelayed(300L) { registerAdActivityCallbacks() }
            return
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass.name == AD_ACTIVITY) {
                    scheduleActivityScan(activity, "callback-create")
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name == AD_ACTIVITY) {
                    scheduleActivityScan(activity, "callback-resume")
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (activity.javaClass.name == AD_ACTIVITY) {
                    activeAdActivities.remove(activity)
                    scheduledActivities.remove(activity)
                }
            }
        })
    }

    private fun scheduleActivityScan(activity: Activity, stage: String) {
        if (activity.javaClass.name != AD_ACTIVITY) return
        activeAdActivities.add(activity)
        if (!scheduledActivities.add(activity)) return
        listOf(0L, 120L, 300L, 600L, 1_000L, 1_800L, 3_000L).forEach { delay ->
            runMainDelayed(delay) {
                val root = runCatching { activity.window?.decorView }.getOrNull()
                if (root == null) return@runMainDelayed
                scanRoot(root, "$stage+$delay", activity)
            }
        }
    }

    private fun scanFrom(view: View, stage: String) {
        val root = runCatching { view.rootView }.getOrNull() ?: return
        if (!pendingRootScans.add(root)) return
        runMainDelayed(80L) {
            try {
                scanRoot(root, stage)
            } finally {
                pendingRootScans.remove(root)
            }
        }
    }

    private fun scanRoot(root: View, stage: String, activity: Activity? = null) {
        if (activity == null && !isKookAdView(root)) return
        var visited = 0

        fun visit(node: View) {
            if (visited++ >= 2_000) return
            if (node.visibility != View.VISIBLE) return
            val named = hasNamedSkipSemantics(node)
            val countdown = isCountdownView(node)
            if (node is WebView && isKookAdWebView(node)) {
                scheduleWebProbe(node, stage)
            }
            if (named || countdown) {
                scheduleClick(node, "scan-$stage")
            }
            (node as? ViewGroup)?.let { group ->
                for (index in 0 until group.childCount) {
                    if (visited >= 2_000) break
                    visit(group.getChildAt(index))
                }
            }
        }

        visit(root)
    }

    private fun scheduleWebProbe(webView: WebView, stage: String) {
        if (!pendingWebChecks.add(webView)) return
        listOf(250L, 700L, 1_300L, 2_000L).forEach { delay ->
            runMainDelayed(delay) {
                if (!pendingWebChecks.contains(webView)) return@runMainDelayed
                if (!isKookAdWebView(webView)) {
                    pendingWebChecks.remove(webView)
                    return@runMainDelayed
                }
                runCatching {
                    webView.evaluateJavascript(WEB_PROBE_SCRIPT) { rawResult ->
                        val result = javascriptString(rawResult)
                        if (result.startsWith("candidate|")) {
                            pendingWebChecks.remove(webView)
                            clickWebCandidate(webView, result, "web-$stage+$delay")
                        } else if (delay == 2_000L) {
                            pendingWebChecks.remove(webView)
                        }
                    }
                }.onFailure {
                    pendingWebChecks.remove(webView)
                }
            }
        }
    }

    private fun clickWebCandidate(webView: WebView, candidate: String, reason: String) {
        if (!candidate.startsWith("candidate|") || !isKookAdWebView(webView)) return

        val now = android.os.SystemClock.uptimeMillis()
        val reserved = synchronized(lastClickAt) {
            val previous = lastClickAt[webView]
            if (previous != null && now - previous < CLICK_COOLDOWN_MS) {
                false
            } else {
                lastClickAt[webView] = now
                true
            }
        }
        if (!reserved) return

        runCatching {
            webView.evaluateJavascript(WEB_CLICK_SCRIPT) { rawResult ->
                val result = javascriptString(rawResult)
                if (!result.startsWith("clicked|")) {
                    lastClickAt.remove(webView)
                }
            }
        }.onFailure {
            lastClickAt.remove(webView)
        }
    }

    private fun isKookAdWebView(view: WebView): Boolean {
        return isKookAdView(view) && runCatching {
            view.isShown && view.isAttachedToWindow
        }.getOrDefault(false)
    }

    private fun javascriptString(value: String?): String {
        return value.orEmpty()
            .trim()
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun scheduleClick(view: View, reason: String) {
        synchronized(pendingClicks) {
            if (!pendingClicks.add(view)) return
        }
        listOf(0L, 180L, 500L, 1_000L).forEach { delay ->
            runMainDelayed(delay) {
                if (!isKookAdView(view) || (!isNamedSkipControl(view) && !isCountdownView(view))) {
                    if (delay == 1_000L) pendingClicks.remove(view)
                    return@runMainDelayed
                }
                val eligible = isVisibleClickable(view)
                if (!eligible) {
                    if (delay == 1_000L) pendingClicks.remove(view)
                    return@runMainDelayed
                }
                val now = android.os.SystemClock.uptimeMillis()
                val previous = lastClickAt[view]
                if (previous != null && now - previous < CLICK_COOLDOWN_MS) {
                    pendingClicks.remove(view)
                    return@runMainDelayed
                }
                val clicked = runCatching { view.performClick() }.getOrDefault(false)
                if (clicked || delay == 1_000L) {
                    pendingClicks.remove(view)
                }
                if (clicked) {
                    lastClickAt[view] = now
                }
            }
        }
    }

    private fun hasNamedSkipSemantics(view: View): Boolean {
        if (view is WebView) return false
        val name = resourceName(view).lowercase(Locale.ROOT)
        if (name.isBlank() || excludedNameTokens.any(name::contains)) return false
        return skipNameTokens.any(name::contains)
    }

    private fun isNamedSkipControl(view: View): Boolean {
        return hasNamedSkipSemantics(view) && isVisibleClickable(view)
    }

    private fun isCountdownView(view: View): Boolean {
        return view.javaClass.name == COUNTDOWN_VIEW ||
            view.javaClass.simpleName.contains("Countdown", ignoreCase = true)
    }

    private fun isVisibleClickable(view: View): Boolean {
        return runCatching {
            val width = view.width
            val height = view.height
            view.isShown && view.isEnabled && view.isAttachedToWindow &&
                width >= 18 && height >= 18 &&
                (view.isClickable || view.hasOnClickListeners())
        }.getOrDefault(false)
    }
    private fun isKookAdView(view: View): Boolean {
        val contextActivity = unwrapActivity(view.context)
        if (contextActivity?.javaClass?.name == AD_ACTIVITY) return true

        val root = runCatching { view.rootView }.getOrNull() ?: return false
        return synchronized(activeAdActivities) {
            activeAdActivities.any { activity ->
                activity.javaClass.name == AD_ACTIVITY &&
                    runCatching { activity.window?.decorView === root }.getOrDefault(false)
            }
        }
    }

    private fun unwrapActivity(context: Context?): Activity? {
        var current = context ?: return null
        repeat(16) {
            if (current is Activity) return current
            val wrapper = current as? ContextWrapper ?: return null
            val next = wrapper.baseContext
            if (next === current) return null
            current = next
        }
        return current as? Activity
    }


    private fun resourceName(view: View): String {
        return runCatching {
            if (view.id == View.NO_ID || view.id == 0) ""
            else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")
    }
}