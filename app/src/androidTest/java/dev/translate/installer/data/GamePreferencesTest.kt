package dev.translate.installer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.translate.installer.domain.GameProfile
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamePreferencesTest {
    @Test
    fun destinationChoicesSurviveNewInstanceAndStaySeparateFromReceiptsAndPermissions() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test_choices_${UUID.randomUUID()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences(namespace, mode)
            }
        try {
            val first = GamePreferences(context)
            first.remember(GameProfile.BETTER_NA)
            first.remember(GameProfile.BA)
            first.rememberInstalled(GameProfile.BETTER_NA, "signed-version", 123)
            val second = GamePreferences(context)
            assertEquals(GameProfile.BETTER_NA, second.selectedFor(GameProfile.JP))
            assertEquals(GameProfile.BA, second.selectedFor(GameProfile.BA))
            assertEquals(123L, second.installedAsset(GameProfile.BETTER_NA, "signed-version"))
            assertNull(second.installedAsset(GameProfile.NA, "signed-version"))
            assertNull(second.installedAsset(GameProfile.BETTER_NA, "other-receipt"))
            second.forgetInstalled(GameProfile.BETTER_NA)
            assertNull(second.installedAsset(GameProfile.BETTER_NA, "signed-version"))
            assertEquals(GameProfile.BETTER_NA, second.selectedFor(GameProfile.NA))
            assertFalse(
                context.getSharedPreferences("ignored", Context.MODE_PRIVATE).contains("permission")
            )
        } finally {
            base.deleteSharedPreferences(namespace)
        }
    }
}
