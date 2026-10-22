package dev.translate.installer.domain

import java.util.Base64

enum class GameProfile(
    val profileId: String,
    val displayName: String,
    val packageName: String,
    val destinationRelativeDirectory: String,
    val contentPolicy: ProfileContentPolicy,
    val isUserSelectable: Boolean,
) {
    JP(
        profileId = "fgo-jp",
        displayName = "Fate/Grand Order — Japão",
        packageName = "com.aniplex.fategrandorder",
        destinationRelativeDirectory = "files/data/d713/",
        contentPolicy = ProfileContentPolicy.FGO_TRANSLATION,
        isUserSelectable = true,
    ),
    NA(
        profileId = "fgo-na",
        displayName = "Fate/Grand Order — América do Norte",
        packageName = "com.aniplex.fategrandorder.en",
        destinationRelativeDirectory = "files/data/d713/",
        contentPolicy = ProfileContentPolicy.FGO_TRANSLATION,
        isUserSelectable = true,
    ),
    BA(
        profileId = "blue-archive-global",
        displayName = "Blue Archive (Global)",
        packageName = "com.nexon.bluearchive",
        destinationRelativeDirectory = "files/PUB/Resource/Preload/TableBundles/",
        contentPolicy = ProfileContentPolicy.BLUE_ARCHIVE_DATABASE,
        isUserSelectable = false,
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
