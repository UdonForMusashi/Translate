package dev.translate.installer.download

import android.app.ActivityManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.translate.installer.data.remote.DownloadStatus
import dev.translate.installer.domain.GameProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starts the real Android service briefly. Never authenticates or installs game files. */
@RunWith(AndroidJUnit4::class)
class DownloadServiceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun foregroundTransferCanOutliveActivityAndCanBeCancelled() {
        compose.setContent { Text("Download service test") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun foreground() =
            manager.getRunningServices(20).any {
                it.service.className == TranslationDownloadService::class.java.name && it.foreground
            }
        try {
            compose.runOnUiThread { BackgroundDownloads.begin(context, GameProfile.NA) }
            compose.waitUntil(10_000) {
                foreground() ||
                    BackgroundDownloads.state.value.status in
                        setOf(DownloadStatus.COMPLETE, DownloadStatus.PAUSED)
            }
            assertNotEquals(DownloadStatus.IDLE, BackgroundDownloads.state.value.status)
            assertEquals(GameProfile.NA, BackgroundDownloads.state.value.profile)
            compose.runOnUiThread { compose.activity.moveTaskToBack(true) }
            Thread.sleep(1_000)
            val state = BackgroundDownloads.state.value
            assertTrue(
                foreground() ||
                    state.status in setOf(DownloadStatus.COMPLETE, DownloadStatus.PAUSED)
            )
        } finally {
            val previousEpoch = BackgroundDownloads.epoch
            val previousSnapshot = BackgroundDownloads.state.value
            BackgroundDownloads.cancel(context)
            assertFalse(BackgroundDownloads.publish(previousSnapshot, previousEpoch))
            runBlocking { BackgroundDownloads.discardCompleted(context, GameProfile.NA) }
        }
        assertEquals(DownloadStatus.IDLE, BackgroundDownloads.state.value.status)
    }
}
