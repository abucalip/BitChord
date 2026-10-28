package com.music.bitchord.data.sources.navidrome

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.EncryptedPrefs

/** The username and password for one Navidrome server. */
data class NavidromeLogin(val username: String, val password: String) {
    // A data class prints its fields; this one must not.
    override fun toString() = "NavidromeLogin(username=$username, password=***)"
}

/**
 * Where Navidrome logins are kept: apart from [SourceConfig][com.music.bitchord.data.sources.SourceConfig],
 * keyed by the source's id.
 *
 * Lives in its own encrypted file, excluded from backups. If the Keystore is
 * unusable [EncryptedPrefs] degrades to a plain file; this store refuses to
 * write a password there ([save] returns false) and the editor asks the user
 * to try again rather than persist a secret in the clear.
 */
object NavidromeCredentials {

    private const val NAME = "bitchord_navidrome"
    private const val PLAIN_NAME = "bitchord_navidrome_plain"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, NAME, PLAIN_NAME)
    }

    /** Whether a password can be stored safely right now. */
    fun canStore(): Boolean = prefs != null && EncryptedPrefs.isEncrypted(NAME)

    fun get(configId: String): NavidromeLogin? {
        val store = prefs ?: return null
        val user = store.getString("$configId.user", null) ?: return null
        val password = store.getString("$configId.password", null) ?: return null
        return NavidromeLogin(user, password)
    }

    /** @return false when the login could not be stored securely, in which case nothing was written. */
    fun save(configId: String, login: NavidromeLogin): Boolean {
        val store = prefs ?: return false
        if (!canStore()) return false
        return store.edit()
            .putString("$configId.user", login.username)
            .putString("$configId.password", login.password)
            .commit()
    }

    /** Removes the login for [configId]; safe to call before [init] or for an unknown id. */
    fun delete(configId: String) {
        prefs?.edit()?.remove("$configId.user")?.remove("$configId.password")?.apply()
    }
}
