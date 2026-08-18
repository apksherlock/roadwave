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
        // Deliberately not calling Bugfender.enableLogcatLogging(): the carLogD/I/W/E
        // helpers in playback/CarLog.kt already call both Log.* and Bugfender.* directly
        // for our own breadcrumbs, so mirroring logcat on top of that double-logs every
        // one of those lines (it picks up the same Log.* call the helper already made).
    }
}
