package dev.translate.installer.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyDirectAccessPolicyTest {
    @Test fun `uses direct access only through Android 9`() {
        assertEquals(InstallationAccessMode.LEGACY_DIRECT, LegacyDirectAccessPolicy.modeFor(24))
        assertEquals(InstallationAccessMode.LEGACY_DIRECT, LegacyDirectAccessPolicy.modeFor(25))
        assertEquals(InstallationAccessMode.LEGACY_DIRECT, LegacyDirectAccessPolicy.modeFor(26))
        assertEquals(InstallationAccessMode.LEGACY_DIRECT, LegacyDirectAccessPolicy.modeFor(28))
        assertEquals(InstallationAccessMode.SHIZUKU, LegacyDirectAccessPolicy.modeFor(29))
        assertEquals(InstallationAccessMode.SHIZUKU, LegacyDirectAccessPolicy.modeFor(36))
    }
}
