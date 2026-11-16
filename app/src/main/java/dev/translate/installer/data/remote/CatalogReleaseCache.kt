package dev.translate.installer.data.remote

import android.content.Context
import dev.translate.installer.domain.GameProfile

/** A short catalog cache. The installer always checks GitHub again before writing. */
class CatalogReleaseCache(context: Context) {
    private val preferences = context.getSharedPreferences("catalog_releases", Context.MODE_PRIVATE)

    fun read(profile: GameProfile, now: Long = System.currentTimeMillis()): ReleaseAsset? {
        val key = profile.bundleProfile.profileId
        val age = now - preferences.getLong("$key.time", 0)
        if (age !in 0..600_000) return null
        val id = preferences.getLong("$key.id", 0)
        val size = preferences.getLong("$key.size", 0)
        val name = preferences.getString("$key.name", null) ?: return null
        val tag = preferences.getString("$key.tag", null) ?: return null
        return ReleaseAsset(id, name, size, tag).takeIf { id > 0 && size > 0 }
    }

    fun save(profile: GameProfile, asset: ReleaseAsset) {
        val key = profile.bundleProfile.profileId
        preferences
            .edit()
            .putLong("$key.time", System.currentTimeMillis())
            .putLong("$key.id", asset.id)
            .putLong("$key.size", asset.size)
            .putString("$key.name", asset.name)
            .putString("$key.tag", asset.tagName)
            .apply()
    }
}
