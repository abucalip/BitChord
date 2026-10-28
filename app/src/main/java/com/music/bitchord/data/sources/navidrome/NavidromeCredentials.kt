package com.music.bitchord.data.sources.navidrome

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.music.bitchord.auth.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Null in production; tests install a store that is not backed by the Keystore. */
    private var storeIsEncrypted: Boolean? = null

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped whenever a stored login changes. A password edited on a server
     * whose address did not change alters no [SourceConfig] field, so anything
     * that re-checks a server when its config changes (the sources screen's
     * health probe) also watches this.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @VisibleForTesting
    internal fun installForTest(store: SharedPreferences, encrypted: Boolean = true) {
        prefs = store
        storeIsEncrypted = encrypted
    }

    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, NAME, PLAIN_NAME)
    }

    /** Whether a password can be stored safely right now. */
    fun canStore(): Boolean = prefs != null && (storeIsEncrypted ?: EncryptedPrefs.isEncrypted(NAME))

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
        val saved = store.edit()
            .putString("$configId.user", login.username)
            .putString("$configId.password", login.password)
            .commit()
        if (saved) _revision.value++
        return saved
    }

    /** Removes the login for [configId]; safe to call before [init] or for an unknown id. */
    fun delete(configId: String) {
        val store = prefs ?: return
        store.edit().remove("$configId.user").remove("$configId.password").commit()
        _revision.value++
    }
}
