package dev.translate.installer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProfileTest {
    @Test
    fun `all profiles resolve only compiled destinations`() {
        assertEquals(decodeTechnical("Y29tLmFuaXBsZXguZmF0ZWdyYW5kb3JkZXI="), GameProfile.JP.packageName)
        assertEquals(decodeTechnical("Y29tLmFuaXBsZXguZmF0ZWdyYW5kb3JkZXIuZW4="), GameProfile.NA.packageName)
        assertEquals("files/data/d713/", GameProfile.JP.destinationRelativeDirectory)
        assertEquals("files/data/d713/", GameProfile.NA.destinationRelativeDirectory)
        assertEquals(GameProfile.JP, GameProfile.fromId(decodeTechnical("ZmdvLWpw")))
        assertEquals(GameProfile.NA, GameProfile.fromId(decodeTechnical("ZmdvLW5h")))
        assertEquals("com.nexon.bluearchive", GameProfile.BA.packageName)
        assertEquals(
            "files/PUB/Resource/Preload/TableBundles/",
            GameProfile.BA.destinationRelativeDirectory,
        )
        assertEquals(
            GameProfile.BA,
            GameProfile.fromId(decodeTechnical("Ymx1ZS1hcmNoaXZlLWdsb2JhbA==")),
        )
        assertNull(GameProfile.fromId("../../outro"))
    }
}
