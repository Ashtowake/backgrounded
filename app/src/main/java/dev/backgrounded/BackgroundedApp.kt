package dev.backgrounded

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dagger.hilt.android.HiltAndroidApp
import dev.backgrounded.data.importer.FolderDiscoveryController
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BackgroundedApp : Application(), Application.ActivityLifecycleCallbacks {
    @Inject lateinit var discovery: FolderDiscoveryController

    @Inject lateinit var operations: dev.backgrounded.data.importer.OperationRecovery

    @Inject @dev.backgrounded.core.di.ApplicationScope
    lateinit var scope: kotlinx.coroutines.CoroutineScope

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        scope.launch { operations.recover() }
    }

    override fun onActivityStarted(activity: Activity) {
        discovery.visible(activity, true)
    }

    override fun onActivityStopped(activity: Activity) {
        discovery.visible(activity, false)
    }

    override fun onActivityDestroyed(activity: Activity) {
        discovery.visible(activity, false)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit
}
