package com.apksherlock.roadwave.playback

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.bugfender.sdk.Bugfender
import java.util.concurrent.atomic.AtomicInteger

/** Single tag so a whole session can be filtered with `adb logcat -s RoadwaveCar`. */
const val CAR_LOG_TAG = "RoadwaveCar"

/**
 * Identifies one process lifetime. Android Auto rebinds after the app process dies, so
 * a failing drive produces several short bursts of logs that look nearly identical on
 * the dashboard. The run id makes it obvious where one attempt ended and the next began.
 */
private val runId: String = Integer.toHexString((SystemClock.elapsedRealtimeNanos() and 0xFFFF).toInt())
    .padStart(4, '0')

private val processStart = SystemClock.elapsedRealtime()

/**
 * Monotonic per-process line counter. A gap in this sequence on the dashboard means
 * Bugfender dropped or has not yet uploaded a line — which is very different from the
 * code never having reached that point, and the two are indistinguishable without it.
 */
private val seq = AtomicInteger()

/**
 * Every line carries run id, sequence, milliseconds since process start, and thread.
 * The elapsed time is what identifies a stall: the interesting signal is usually a
 * large jump between two adjacent lines, not any line's content.
 */
private fun decorate(message: String): String {
    val n = seq.incrementAndGet()
    val elapsed = SystemClock.elapsedRealtime() - processStart
    return "[$runId/$n +${elapsed}ms ${Thread.currentThread().name}] $message"
}

internal fun carLogI(message: String) {
    val m = decorate(message)
    Log.i(CAR_LOG_TAG, m)
    Bugfender.i(CAR_LOG_TAG, m)
}

internal fun carLogD(message: String) {
    val m = decorate(message)
    Log.d(CAR_LOG_TAG, m)
    Bugfender.d(CAR_LOG_TAG, m)
}

internal fun carLogW(message: String) {
    val m = decorate(message)
    Log.w(CAR_LOG_TAG, m)
    Bugfender.w(CAR_LOG_TAG, m)
}

internal fun carLogE(message: String, throwable: Throwable? = null) {
    val m = decorate(if (throwable != null) "$message: ${throwable.stackTraceToString()}" else message)
    Log.e(CAR_LOG_TAG, m, throwable)
    Bugfender.e(CAR_LOG_TAG, m)
}

/**
 * Fatal level, which Bugfender surfaces separately from errors. Reserved for something
 * that ends the process — see the uncaught-exception handler in `App`.
 */
internal fun carLogF(message: String, throwable: Throwable? = null) {
    val m = decorate(if (throwable != null) "$message: ${throwable.stackTraceToString()}" else message)
    Log.e(CAR_LOG_TAG, m, throwable)
    Bugfender.f(CAR_LOG_TAG, m)
}

/**
 * Asks Bugfender to upload now rather than on its own cadence, and records why.
 *
 * Worth calling at any point after which the process might not survive: an unplugged
 * phone or a host-initiated kill otherwise strands the interesting lines in the local
 * queue until the app happens to run again.
 */
internal fun carLogFlush(reason: String) {
    carLogD("flush requested: $reason")
    runCatching { Bugfender.forceSendOnce() }
        .onFailure { Log.w(CAR_LOG_TAG, "forceSendOnce() failed", it) }
}

/**
 * Full description of a host-supplied [Intent].
 *
 * The launch intent is the single biggest behavioural difference between the Desktop
 * Head Unit and a real head unit — the DHU's template launcher sends a bare intent
 * while a car's media launcher sends a playback deep link — so it is worth dumping in
 * full rather than logging the action alone.
 */
internal fun describeIntent(intent: Intent?): String {
    if (intent == null) return "intent=null"
    return runCatching {
        val extras = intent.extras
        val extrasText = if (extras == null) {
            "none"
        } else {
            extras.keySet().joinToString(", ") { key ->
                // Bundle.get(String) is deprecated with no replacement that works for
                // values of unknown type, which is precisely the case here — these
                // extras come from the host and their shape is undocumented. Each read
                // is guarded because get() unparcels, and a class the host has but we
                // do not throws BadParcelableException.
                @Suppress("DEPRECATION")
                val value = runCatching { extras.get(key) }.getOrElse { "<unreadable>" }
                "$key=$value"
            }
        }
        buildString {
            append("action=").append(intent.action)
            append(" categories=").append(intent.categories?.joinToString(",") ?: "none")
            append(" data=").append(intent.dataString)
            append(" type=").append(intent.type)
            append(" component=").append(intent.component?.flattenToShortString())
            append(" flags=0x").append(Integer.toHexString(intent.flags))
            append(" extras={").append(extrasText).append("}")
        }
    }.getOrElse { "intent=<failed to describe: $it>" }
}
