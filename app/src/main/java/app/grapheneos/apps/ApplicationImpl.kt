package app.grapheneos.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.os.postDelayed
import app.grapheneos.apps.autoupdate.AutoUpdatePrefs
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.core.notificationManager
import app.grapheneos.apps.util.ActivityUtils
import com.google.android.material.color.DynamicColors

class ApplicationImpl : Application(), ActivityLifecycleCallbacks {
    companion object {
        @SuppressLint("StaticFieldLeak") // app context is a singleton
        lateinit var baseAppContext: Context

        const val JOB_SCHEDULER_JOB_ID_AUTO_UPDATE = 1000
    }

    // called before ContentProviders are initialized, onCreate() is called after
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        baseAppContext = base

        PackageStates.requestRepoUpdateNoSuspend()
        PackageStates.init()

        val activeNotifications = notificationManager.activeNotifications
        Notifications.init(activeNotifications)
        ActivityUtils.init(activeNotifications)

        // if the MainActivity is being launched for the first time, AutoUpdateJob would be executed
        // before it launches, which breaks the check in AutoUpdateJob that skips the job when
        // an activity is visible. Delay the job setup to avoid this.
        mainHandler.postDelayed(3000) {
            AutoUpdatePrefs.setupJob()
        }

        DynamicColors.applyToActivitiesIfAvailable(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        PackageStates.onResourceConfigChanged()
    }

    override fun onActivityResumed(activity: Activity) {
        ActivityUtils.onActivityResumedOrPaused(activity, true)
    }

    override fun onActivityPaused(activity: Activity) {
        ActivityUtils.onActivityResumedOrPaused(activity, false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
