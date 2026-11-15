package dev.translate.installer.data

import android.content.Context
import dev.translate.installer.domain.GameProfile

/** Only the user's explicit destination choices are remembered, never permissions. */
class GamePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("game_choices", Context.MODE_PRIVATE)

    fun selectedFor(game: GameProfile): GameProfile? =
        preferences.getString(key(game), null)?.let(GameProfile::fromId)?.takeIf {
            it.isUserSelectable &&
                (it.bundleProfile == GameProfile.BA) == (game.bundleProfile == GameProfile.BA)
        }

    fun remember(profile: GameProfile) {
        preferences.edit().putString(key(profile), profile.profileId).apply()
    }

    fun installedAsset(profile: GameProfile, receiptVersion: String?): Long? {
        if (
            receiptVersion == null ||
                preferences.getString("${profile.profileId}.installed_version", null) !=
                    receiptVersion
        )
            return null
        return preferences.getLong("${profile.profileId}.installed_asset", 0).takeIf { it > 0 }
    }

    fun rememberInstalled(profile: GameProfile, version: String, assetId: Long) {
        preferences
            .edit()
            .putString("${profile.profileId}.installed_version", version)
            .putLong("${profile.profileId}.installed_asset", assetId)
            .apply()
    }

    fun forgetInstalled(profile: GameProfile) {
        preferences
            .edit()
            .remove("${profile.profileId}.installed_version")
            .remove("${profile.profileId}.installed_asset")
            .apply()
    }

    private fun key(profile: GameProfile) =
        if (profile.bundleProfile == GameProfile.BA) "blue_archive" else "fgo"
}
