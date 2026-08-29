package dev.translate.installer.domain

import java.util.Base64

enum class GameProfile(
    val profileId: String,
    val displayName: String,
    val packageName: String,
    val destinationRelativeDirectory: String,
) {
    JP(
        profileId = decodeTechnical("ZmdvLWpw"),
        displayName = "JP",
        packageName = decodeTechnical("Y29tLmFuaXBsZXguZmF0ZWdyYW5kb3JkZXI="),
        destinationRelativeDirectory = "files/data/d713/",
    ),
    NA(
        profileId = decodeTechnical("ZmdvLW5h"),
        displayName = "NA",
        packageName = decodeTechnical("Y29tLmFuaXBsZXguZmF0ZWdyYW5kb3JkZXIuZW4="),
        destinationRelativeDirectory = "files/data/d713/",
    );

    companion object {
        fun fromId(profileId: String): GameProfile? =
            entries.firstOrNull { it.profileId == profileId }
    }
}

internal fun decodeTechnical(encoded: String): String =
    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
