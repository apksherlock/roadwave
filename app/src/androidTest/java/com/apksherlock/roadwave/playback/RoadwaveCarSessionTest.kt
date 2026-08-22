package com.apksherlock.roadwave.playback

import android.content.Intent
import androidx.car.app.ScreenManager
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the launch path that only a real head unit exercises.
 *
 * The Desktop Head Unit starts this app from its template launcher with a bare intent
 * (`action=null`, confirmed against a real DHU session), so the playback deep-link
 * branch never ran there and the bug below was invisible for every local test. A car's
 * media launcher sends a playback action instead.
 *
 * [SessionController] reproduces the condition faithfully because it drives ON_CREATE
 * exactly the way [androidx.car.app.CarAppBinder.onAppCreate] does — dispatch the
 * lifecycle event, then push whatever `onCreateScreen` returns:
 *
 * ```java
 * registry.handleLifecycleEvent(Event.ON_CREATE);
 * screenManager.push(mSession.onCreateScreen(mIntent));
 * ```
 *
 * The screen stack is therefore still empty while `onCreateScreen` runs, which is what
 * made `ScreenManager.getTop()` throw NullPointerException there. Before the fix, the
 * two deep-link tests failed with that NPE; the null-action test passed, exactly
 * mirroring car-versus-DHU.
 */
@RunWith(AndroidJUnit4::class)
class RoadwaveCarSessionTest {

    /**
     * Runs a session launch on the main thread and returns the resulting screen stack,
     * top first.
     *
     * Everything happens inside `runOnMainSync` because [ScreenManager.push] calls
     * `checkMainThread()`. The failure is re-thrown on the test thread rather than left
     * to `runOnMainSync`, so a regression surfaces as this test failing instead of as an
     * unrelated crash in the instrumentation process.
     */
    private fun launchWith(action: String?): List<String> {
        var stack: List<String> = emptyList()
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching {
                val carContext = TestCarContext.createCarContext(
                    ApplicationProvider.getApplicationContext()
                )
                val intent = Intent().also { if (action != null) it.action = action }
                SessionController(RoadwaveCarSession(), carContext, intent)
                    .moveToState(Lifecycle.State.CREATED)
                stack = carContext.getCarService(ScreenManager::class.java)
                    .screenStack
                    .map { it.javaClass.simpleName }
            }.onFailure { failure = it }
        }
        failure?.let { throw AssertionError("Session launch with action=$action threw", it) }
        return stack
    }

    @Test
    fun templateLauncherIntent_startsAtBrowseRoot() {
        // What the DHU sends. This is the case that always passed.
        assertEquals(listOf("MainCarScreen"), launchWith(null))
    }

    @Test
    fun mediaPlaybackDeepLink_landsOnPlaybackAboveBrowseRoot() {
        // The documented action a car's media launcher sends. Asserting the order too:
        // the original code pushed PlaybackScreen first and returned MainCarScreen, so
        // even without the NPE the deep link would have been buried under the root.
        assertEquals(
            listOf("PlaybackScreen", "MainCarScreen"),
            launchWith("androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK")
        )
    }

    @Test
    fun hostSpecificDeepLinkVariant_isRecognised() {
        // Real hosts have been observed sending this non-standard action, which
        // isShowPlaybackAction() matches by substring.
        assertEquals(
            listOf("PlaybackScreen", "MainCarScreen"),
            launchWith("MEDIA_SHOW_PLAYBACK_VIEW")
        )
    }
}
