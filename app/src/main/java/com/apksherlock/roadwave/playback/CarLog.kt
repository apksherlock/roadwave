package com.apksherlock.roadwave.playback

import android.util.Log
import com.bugfender.sdk.Bugfender

/** Single tag so a whole session can be filtered with `adb logcat -s RoadwaveCar`. */
const val CAR_LOG_TAG = "RoadwaveCar"

/**
 * Logs to both logcat (for live `adb logcat` debugging) and Bugfender. Bugfender
 * writes to its own locally-persisted queue immediately, so these breadcrumbs are
 * far more likely to survive a sudden process death and make it to the dashboard
 * than logcat alone — which is exactly the failure mode under investigation.
 */
internal fun carLogI(message: String) {
    Log.i(CAR_LOG_TAG, message)
    Bugfender.i(CAR_LOG_TAG, message)
}

internal fun carLogD(message: String) {
    Log.d(CAR_LOG_TAG, message)
    Bugfender.d(CAR_LOG_TAG, message)
}

internal fun carLogW(message: String) {
    Log.w(CAR_LOG_TAG, message)
    Bugfender.w(CAR_LOG_TAG, message)
}

internal fun carLogE(message: String, throwable: Throwable? = null) {
    Log.e(CAR_LOG_TAG, message, throwable)
    Bugfender.e(CAR_LOG_TAG, if (throwable != null) "$message: $throwable" else message)
}
