package com.hujiayucc.hook.hooker.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.content.ContextWrapper
import dalvik.system.BaseDexClassLoader
import io.github.libxposed.api.XposedModuleInterface
import java.util.Collections
import java.util.WeakHashMap

internal class JiaGuHookCoordinator(
    private val owner: Hooker,
    private val appName: () -> String,
    private val currentClassLoader: () -> ClassLoader?,
    private val updateClassLoader: (ClassLoader) -> Unit,
    private val markerClasses: () -> List<String>,
    private val retryDelays: () -> List<Long>,
    private val enableLoadClassProbe: () -> Boolean
) {
    fun run(param: XposedModuleInterface.PackageReadyParam) {
        if (markerClasses().isEmpty()) {
            HookerLogger.hookError(
                "Skip ${appName()} JiaGu hook because marker classes are empty",
                IllegalStateException("@RunJiaGu requires marker classes for real ClassLoader verification.")
            )
            return
        }

        val state = State(param)
        // 先挂平台级探针，再处理当前 Loader；这样当前 Loader 已命中时，后续易盾 Loader 仍会被捕获。
        state.installLifecycleHooks()
        state.installDexClassLoaderHooks()
        if (enableLoadClassProbe()) state.installLoadClassProbe()
        if (state.tryClassLoader(currentClassLoader(), "PackageReadyParam")) return
        state.scheduleRetries()
    }

    private inner class State(
        private val param: XposedModuleInterface.PackageReadyParam
    ) {
        private val executedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val refreshScheduledLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val hookInProgressLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val registeredApplications = Collections.synchronizedMap(WeakHashMap<Application, Boolean>())
        private val observedActivities = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

        @Volatile
        private var verifyingMarker = false

        fun installLifecycleHooks() = with(owner) {
            runCatching {
                ContextWrapper::class.java.method("attachBaseContext", Context::class.java).hook {
                    after {
                        (instance as? Application)?.let { application ->
                            collectFromApplication(application, "Application.attachBaseContext")
                        }
                        (args.firstOrNull() as? Context)?.let { context ->
                            tryClassLoader(context.classLoader, "Application.attachBaseContext context")
                            collectFromLoadedApk(context, "Application.attachBaseContext loadedApk")
                        }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to install attachBaseContext probe for ${appName()}", error)
            }

            runCatching {
                Application::class.java.method("onCreate").hook {
                    after { collectFromApplication(instance<Application>(), "Application.onCreate") }
                }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to install Application.onCreate probe for ${appName()}", error)
            }

            runCatching {
                Instrumentation::class.java.method(
                    "callApplicationOnCreate",
                    Application::class.java
                ).hook {
                    after {
                        (args.firstOrNull() as? Application)?.let { application ->
                            collectFromApplication(application, "Instrumentation.callApplicationOnCreate")
                        }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError(
                    "Failed to install Instrumentation.callApplicationOnCreate probe for ${appName()}",
                    error
                )
            }

            runCatching {
                Instrumentation::class.java.method(
                    "callActivityOnCreate",
                    Activity::class.java,
                    Bundle::class.java
                ).hook {
                    after {
                        (args.firstOrNull() as? Activity)?.let { activity ->
                            collectFromActivity(activity, "Instrumentation.callActivityOnCreate")
                        }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError(
                    "Failed to install Instrumentation.callActivityOnCreate probe for ${appName()}",
                    error
                )
            }

            runCatching {
                Instrumentation::class.java.method(
                    "callActivityOnResume",
                    Activity::class.java
                ).hook {
                    after {
                        (args.firstOrNull() as? Activity)?.let { activity ->
                            collectFromActivity(activity, "Instrumentation.callActivityOnResume")
                        }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError(
                    "Failed to install Instrumentation.callActivityOnResume probe for ${appName()}",
                    error
                )
            }

            runCatching {
                Activity::class.java.method("onResume").hook {
                    after { collectFromActivity(instance<Activity>(), "Activity.onResume") }
                }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to install Activity.onResume probe for ${appName()}", error)
            }

            runCatching {
                Activity::class.java.method(
                    "onWindowFocusChanged",
                    Boolean::class.javaPrimitiveType!!
                ).hook {
                    after {
                        if (args.firstOrNull() == true) {
                            collectFromActivity(instance<Activity>(), "Activity.onWindowFocusChanged")
                        }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError(
                    "Failed to install Activity.onWindowFocusChanged probe for ${appName()}",
                    error
                )
            }

            HookerLogger.info("[AdHook] event=probe_install app=${appName()} stage=platform")
            installLoadedApkHook()
        }

        private fun installLoadedApkHook() = with(owner) {
            runCatching {
                val loadedApkClass = HookerReflectionCache.loadedApkClass()
                HookerReflectionCache.declaredMethods(loadedApkClass)
                    .filter { method ->
                        method.name == "makeApplication" &&
                            Application::class.java.isAssignableFrom(method.returnType)
                    }
                    .forEach { method ->
                        method.hook {
                            after {
                                (result as? Application)?.let { application ->
                                    collectFromApplication(application, "LoadedApk.makeApplication")
                                }
                                tryClassLoader(
                                    owner.getField(instance, "mClassLoader") as? ClassLoader,
                                    "LoadedApk.makeApplication mClassLoader"
                                )
                            }
                        }
                    }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to install LoadedApk.makeApplication probe for ${appName()}", error)
            }
        }

        fun scheduleRetries() {
            retryDelays().forEach { delay ->
                HookerRetryScheduler.postDelayed(delay) {
                    collectCurrentLoaders("retry ${delay}ms")
                }
            }
        }

        fun installDexClassLoaderHooks() = with(owner) {
            runCatching {
                BaseDexClassLoader::class.java.constructor()?.forEach { constructor ->
                    constructor.hook {
                        after { tryClassLoader(instance<ClassLoader>(), "BaseDexClassLoader.constructor") }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to install BaseDexClassLoader constructor probe for ${appName()}", error)
            }
        }

        fun installLoadClassProbe() = with(owner) {
            runCatching {
                ClassLoader::class.java.method("loadClass", String::class.java).hook {
                    after {
                        if (verifyingMarker) return@after
                        val className = args.firstOrNull() as? String ?: return@after
                        if (className !in markerClasses()) return@after
                        val loader = instance<ClassLoader>()
                        if (executedLoaders.containsKey(loader)) {
                            scheduleLoaderRefresh(loader, "ClassLoader.loadClass($className)")
                        } else {
                            tryClassLoader(loader, "ClassLoader.loadClass($className)")
                        }
                    }
                }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to install ClassLoader.loadClass probe for ${appName()}", error)
            }
        }

        fun tryClassLoader(loader: ClassLoader?, source: String): Boolean {
            val candidate = loader ?: return false
            val marker = firstLoadableMarker(candidate) ?: return false
            return runWithClassLoader(candidate, source, marker)
        }

        private fun scheduleLoaderRefresh(loader: ClassLoader, source: String) {
            if (refreshScheduledLoaders.putIfAbsent(loader, true) != null) return
            HookerRetryScheduler.postDelayed(120L) {
                if (hookInProgressLoaders.putIfAbsent(loader, true) != null) return@postDelayed
                runCatching {
                    updateClassLoader(loader)
                    HookerLogger.info(
                        "[AdHook] event=loader_refresh app=${appName()} source=$source " +
                            "loader=${loader.javaClass.name} id=${System.identityHashCode(loader)}"
                    )
                    owner.runHookFromCoordinator(param, loader)
                }.onFailure { error ->
                    HookerLogger.hookError("Failed to refresh hooks for ${appName()}", error)
                }.also {
                    hookInProgressLoaders.remove(loader)
                }
            }
        }

        private fun collectCurrentLoaders(source: String) {
            tryClassLoader(currentClassLoader(), "$source current")
            tryClassLoader(param.classLoader, "$source param")
            tryClassLoader(Thread.currentThread().contextClassLoader, "$source thread")
            runCatching {
                HookerReflectionCache.currentApplication()?.let { application ->
                    collectFromApplication(application, "$source currentApplication")
                }
            }.onFailure { error ->
                HookerLogger.hookError("Failed to query currentApplication for ${appName()}", error)
            }
        }

        private fun registerActivityCallbacks(application: Application) {
            if (registeredApplications.putIfAbsent(application, true) != null) return
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    collectFromActivity(activity, "Application.ActivityLifecycleCallbacks.onActivityCreated")
                }

                override fun onActivityStarted(activity: Activity) {
                    collectFromActivity(activity, "Application.ActivityLifecycleCallbacks.onActivityStarted")
                }

                override fun onActivityResumed(activity: Activity) {
                    collectFromActivity(activity, "Application.ActivityLifecycleCallbacks.onActivityResumed")
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            HookerLogger.info("[AdHook] event=activity_callbacks_registered app=${appName()}")
        }

        private fun collectFromActivity(activity: Activity, source: String) {
            if (observedActivities.putIfAbsent(activity, true) == null) {
                HookerLogger.info(
                    "[AdHook] event=activity_probe app=${appName()} source=$source " +
                        "class=${activity.javaClass.name} loader=${activity.javaClass.classLoader?.javaClass?.name} " +
                        "id=${System.identityHashCode(activity.javaClass.classLoader)}"
                )
            }
            tryClassLoader(activity.javaClass.classLoader, "$source activity")
        }

        private fun collectFromApplication(application: Application, source: String) {
            registerActivityCallbacks(application)
            tryClassLoader(application.classLoader, "$source application")
            application.baseContext?.let { baseContext ->
                tryClassLoader(baseContext.classLoader, "$source baseContext")
                collectFromLoadedApk(baseContext, "$source baseContext loadedApk")
            }
            collectFromLoadedApk(application, "$source loadedApk")
        }

        @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
        private fun collectFromLoadedApk(context: Context, source: String) {
            runCatching {
                val contextImplClass = HookerReflectionCache.contextImplClass()
                if (!contextImplClass.isInstance(context)) return@runCatching
                val packageInfo = HookerReflectionCache.declaredField(contextImplClass, "mPackageInfo")
                    .get(context)
                val loadedApkClassLoader = HookerReflectionCache.declaredField(
                    HookerReflectionCache.loadedApkClass(),
                    "mClassLoader"
                ).get(packageInfo) as? ClassLoader
                tryClassLoader(loadedApkClassLoader, source)
            }.onFailure { error ->
                HookerLogger.hookError(
                    "Failed to collect LoadedApk ClassLoader for ${appName()} from $source",
                    error
                )
            }
        }

        private fun firstLoadableMarker(loader: ClassLoader): String? {
            return markerClasses().firstOrNull { marker ->
                runCatching {
                    verifyingMarker = true
                    val clazz = Class.forName(marker, false, loader)
                    clazz.classLoader === loader
                }.getOrDefault(false).also { verifyingMarker = false }
            }
        }

        @Synchronized
        private fun runWithClassLoader(loader: ClassLoader, source: String, marker: String): Boolean {
            val firstForLoader = executedLoaders.putIfAbsent(loader, true) == null
            if (firstForLoader) {
                HookerLogger.info(
                    "[AdHook] event=loader_ready app=${appName()} source=$source " +
                        "marker=$marker loader=${loader.javaClass.name} " +
                        "id=${System.identityHashCode(loader)}"
                )
            } else if (!source.startsWith("ClassLoader.loadClass(")) {
                return true
            }
            updateClassLoader(loader)
            owner.runHookFromCoordinator(param, loader)
            return true
        }
    }
}