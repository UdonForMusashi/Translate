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
        assertEquals(false, GameProfile.BA.isUserSelectable)
        assertNull(GameProfile.fromId("../../outro"))
    }
}
