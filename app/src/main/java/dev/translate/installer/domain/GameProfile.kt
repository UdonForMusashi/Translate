package dev.translate.installer.domain

import java.util.Base64

enum class GameProfile(
    val profileId: String,
    val displayName: String,
    val packageName: String,
    val destinationRelativeDirectory: String,
    val contentPolicy: ProfileContentPolicy,
) {
    JP(
        profileId = decodeTechnical("ZmdvLWpw"),
        displayName = "FGO JP",
        packageName = decodeTechnical("Y29tLmFuaXBsZXguZmF0ZWdyYW5kb3JkZXI="),
        destinationRelativeDirectory = "files/data/d713/",
        contentPolicy = ProfileContentPolicy.FGO_TRANSLATION,
    ),
    NA(
        profileId = decodeTechnical("ZmdvLW5h"),
        displayName = "FGO NA",
        packageName = decodeTechnical("Y29tLmFuaXBsZXguZmF0ZWdyYW5kb3JkZXIuZW4="),
        destinationRelativeDirectory = "files/data/d713/",
        contentPolicy = ProfileContentPolicy.FGO_TRANSLATION,
    ),
    BA(
        profileId = decodeTechnical("Ymx1ZS1hcmNoaXZlLWdsb2JhbA=="),
        displayName = "Blue Archive (Global)",
        packageName = decodeTechnical("Y29tLm5leG9uLmJsdWVhcmNoaXZl"),
        destinationRelativeDirectory = "files/PUB/Resource/Preload/TableBundles/",
        contentPolicy = ProfileContentPolicy.BLUE_ARCHIVE_DATABASE,
    );

    companion object {
        fun fromId(profileId: String): GameProfile? =
            entries.firstOrNull { it.profileId == profileId }
    }
}

enum class ProfileContentPolicy {
    FGO_TRANSLATION,
    BLUE_ARCHIVE_DATABASE;

    fun isAllowedFileName(name: String): Boolean = when (this) {
        FGO_TRANSLATION -> name == FGO_SPECIAL_FILE || FGO_BIN_NAME.matches(name)
        BLUE_ARCHIVE_DATABASE -> name == BLUE_ARCHIVE_DATABASE_NAME
    }

    fun isValidFileNameSet(names: Set<String>): Boolean = when (this) {
        FGO_TRANSLATION -> FGO_SPECIAL_FILE in names && names.any { FGO_BIN_NAME.matches(it) }
        BLUE_ARCHIVE_DATABASE -> names == setOf(BLUE_ARCHIVE_DATABASE_NAME)
    }

    fun mayCreateMissingFile(name: String): Boolean =
        this == FGO_TRANSLATION && FGO_BIN_NAME.matches(name)

    companion object {
        const val FGO_SPECIAL_FILE = "cfb1d36393fd67385e046b084b7cf7ed"
        const val BLUE_ARCHIVE_DATABASE_NAME = "ExcelDB.db"
        val FGO_BIN_NAME = Regex("[A-Za-z0-9_-]{1,128}\\.bin")
    }
}

internal fun decodeTechnical(encoded: String): String =
    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
