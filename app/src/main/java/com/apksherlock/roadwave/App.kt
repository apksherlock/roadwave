package com.apksherlock.roadwave

import android.app.Application
import android.os.Build
import com.apksherlock.roadwave.playback.carLogF
import com.apksherlock.roadwave.playback.carLogFlush
import com.apksherlock.roadwave.playback.carLogI
import com.bugfender.sdk.Bugfender

/** Bugfender's local queue ceiling. Its documented range is 1 MB to 50 MB. */
private const val MAX_LOCAL_LOG_BYTES = 50L * 1024 * 1024

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // App key comes from secrets.properties (gitignored) via BuildConfig — see
        // secrets.properties.example. Get your own key at https://app.bugfender.com.
        Bugfender.init(this, BuildConfig.BUGFENDER_APP_KEY, BuildConfig.DEBUG, true)
        Bugfender.enableCrashReporting()
        Bugfender.enableUIEventLogging(this)
        // Deliberately not calling Bugfender.enableLogcatLogging(): the carLogD/I/W/E
        // helpers in playback/CarLog.kt already call both Log.* and Bugfender.* directly
        // for our own breadcrumbs, so mirroring logcat on top of that double-logs every
        // one of those lines (it picks up the same Log.* call the helper already made).

        // Without this, Bugfender only uploads log *content* for devices that have been
        // enabled in the Console — every other device's lines arrive as "This is a
        // Bugfender placeholder for older logs", which is exactly what a car session
        // looked like on the dashboard. forceSendOnce() is not a substitute: it syncs
        // once and then reverts to the Console's enabled flag.
        Bugfender.setForceEnabled(true)

        // A car session can be long and entirely offline, and the failure we care about
        // happens in the first seconds of it. The default 5 MB queue can rotate that
        // away before the phone regains connectivity; 50 MB is the documented ceiling.
        Bugfender.setMaximumLocalStorageSize(MAX_LOCAL_LOG_BYTES)

        recordDeviceKeys()
        installFatalHandler()

        carLogI(
            "App.onCreate() — build=${BuildConfig.BUILD_TIME} version=${BuildConfig.VERSION_NAME} " +
                "debug=${BuildConfig.DEBUG} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    /**
     * Device keys show on the Bugfender device panel even for a session that logs
     * nothing else, so a session that dies before producing breadcrumbs can still be
     * told apart from one running a stale build.
     */
    private fun recordDeviceKeys() {
        Bugfender.setDeviceString("build_time", BuildConfig.BUILD_TIME)
        Bugfender.setDeviceString("version_name", BuildConfig.VERSION_NAME)
        Bugfender.setDeviceString("android_release", Build.VERSION.RELEASE)
        Bugfender.setDeviceInteger("android_sdk", Build.VERSION.SDK_INT)
        Bugfender.setDeviceString("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
        Bugfender.setDeviceBoolean("debug_build", BuildConfig.DEBUG)
    }

    /**
     * Chains ahead of Bugfender's own crash reporter so a process-ending throwable also
     * lands in the log stream, tagged and in sequence with the breadcrumbs around it.
     *
     * This matters for the Android Auto path specifically: the car app service runs with
     * no visible component on the phone, so a fatal exception there kills the process
     * without any "app has stopped" dialog. The Crashes tab records it, but reading it
     * next to the surrounding breadcrumbs is what actually shows how far the session got.
     *
     * Installed *after* enableCrashReporting() so this handler runs first and then hands
     * off to Bugfender's, which in turn hands off to the platform default.
     */
    private fun installFatalHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // The queue is written to local storage synchronously, so this line survives
            // the process even though the upload it requests almost certainly will not
            // finish — it goes out on the next run instead.
            runCatching {
                carLogF("FATAL uncaught exception on thread=${thread.name}", throwable)
                carLogFlush("uncaught exception")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
