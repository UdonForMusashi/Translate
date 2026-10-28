package dev.translate.installer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProfileTest {
    @Test
    fun `all profiles resolve only compiled destinations`() {
        assertEquals("com.aniplex.fategrandorder", GameProfile.JP.packageName)
        assertEquals("com.aniplex.fategrandorder.en", GameProfile.NA.packageName)
        assertEquals("files/data/d713/", GameProfile.JP.destinationRelativeDirectory)
        assertEquals("files/data/d713/", GameProfile.NA.destinationRelativeDirectory)
        assertEquals(GameProfile.JP, GameProfile.fromId("fgo-jp"))
        assertEquals(GameProfile.NA, GameProfile.fromId("fgo-na"))
        assertEquals("io.rayshift.betterfgo", GameProfile.BETTER_JP.packageName)
        assertEquals("io.rayshift.betterfgo.en", GameProfile.BETTER_NA.packageName)
        assertEquals(GameProfile.JP, GameProfile.BETTER_JP.bundleProfile)
        assertEquals(GameProfile.NA, GameProfile.BETTER_NA.bundleProfile)
        assertEquals(GameProfile.BETTER_JP, GameProfile.JP.withBetterFgo(true))
        assertEquals(GameProfile.BETTER_NA, GameProfile.NA.withBetterFgo(true))
        assertEquals(GameProfile.JP, GameProfile.BETTER_JP.withBetterFgo(false))
        assertEquals(GameProfile.NA, GameProfile.BETTER_NA.withBetterFgo(false))
        assertEquals(
            GameProfile.BETTER_JP,
            GameProfile.fromId("better-fgo-jp"),
        )
        assertEquals(
            GameProfile.BETTER_NA,
            GameProfile.fromId("better-fgo-na"),
        )
        assertEquals("com.nexon.bluearchive", GameProfile.BA.packageName)
        assertEquals(
            "files/PUB/Resource/Preload/TableBundles/",
            GameProfile.BA.destinationRelativeDirectory,
        )
        assertEquals(
            GameProfile.BA,
            GameProfile.fromId("blue-archive-global"),
        )
        assertEquals(true, GameProfile.JP.isUserSelectable)
        assertEquals(true, GameProfile.NA.isUserSelectable)
        assertEquals(true, GameProfile.BA.isUserSelectable)
        assertEquals(true, GameProfile.BETTER_JP.isUserSelectable)
        assertEquals(true, GameProfile.BETTER_NA.isUserSelectable)
        assertNull(GameProfile.fromId("../../outro"))
    }
}
