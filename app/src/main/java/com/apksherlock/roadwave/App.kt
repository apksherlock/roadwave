package com.apksherlock.roadwave

import android.app.Application
import com.bugfender.sdk.Bugfender

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // App key comes from secrets.properties (gitignored) via BuildConfig — see
        // secrets.properties.example. Get your own key at https://app.bugfender.com.
        Bugfender.init(this, BuildConfig.BUGFENDER_APP_KEY, BuildConfig.DEBUG, true)
        Bugfender.enableCrashReporting()
        Bugfender.enableUIEventLogging(this)
        // Catch-all for anything that logs via plain Log.* instead of the
        // carLogD/I/W/E helpers in playback/CarLog.kt (which write to Bugfender
        // directly and are the primary path for our own diagnostic breadcrumbs).
        Bugfender.enableLogcatLogging()
    }
}
