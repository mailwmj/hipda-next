package net.jejer.hipda.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidApp
class HiNextApplication : Application(), Configuration.Provider, Application.ActivityLifecycleCallbacks {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        visibleActivityCount.set(0)
        registerActivityLifecycleCallbacks(this)
        CrashLogExceptionHandler.install(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        visibleActivityCount.incrementAndGet()
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        visibleActivityCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private val visibleActivityCount = AtomicInteger(0)

        fun isAppVisible(): Boolean = visibleActivityCount.get() > 0
    }
}
