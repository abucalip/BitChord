package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.music.bitchord.R
import com.music.bitchord.data.sources.NavidromeSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.navidrome.NavidromeClient
import com.music.bitchord.data.sources.navidrome.NavidromeCredentials
import com.music.bitchord.data.sources.navidrome.NavidromeLogin
import com.music.bitchord.ui.components.EditorField
import com.music.bitchord.ui.components.ServerEditorAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add or edit a Navidrome server: address, username, password and an optional name.
 *
 * Test and Save run the same health check. Save stores a server that failed it
 * (one that is asleep is still worth keeping) but never stores a password it
 * could not keep safely, or an address that is not a usable http(s) URL.
 *
 * An address typed without a scheme is taken as https. Plain http works —
 * LAN servers need it — but only when the user typed `http://` themselves, and
 * the card says so for as long as it is there.
 *
 * When editing, a blank password field means "keep the stored one"; the stored
 * password is never read back into the field.
 */
@Composable
internal fun NavidromeEditorAlert(
    hazeState: HazeState,
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDelete: () -> Unit,
    scope: CoroutineScope,
) {
    val isNew = SourceRegistry.config(config.id) == null
    val stored = remember(config.id) { NavidromeCredentials.get(config.id) }
    var url by remember { mutableStateOf(config.baseUrl) }
    var username by remember { mutableStateOf(stored?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(config.label) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }

    val connected = stringResource(R.string.connected)
    val invalidUrl = stringResource(R.string.navidrome_invalid_url)
    val alreadyAdded = stringResource(R.string.source_already_added)
    val storageUnavailable = stringResource(R.string.navidrome_secure_storage_unavailable)
    val passwordRequired = stringResource(R.string.navidrome_password_required)

    fun typedUrl() = url.trim().let { if ("://" in it || it.isEmpty()) it else "https://$it" }

    fun run(thenSave: Boolean) {
        val address = NavidromeClient.normalizeBase(typedUrl())?.toString()?.trimEnd('/')
        if (address == null || address.substringBefore("://") !in setOf("http", "https")) {
            statusIsGood = false
            status = invalidUrl
            return
        }
        val login = NavidromeLogin(
            username = username.trim(),
            password = password.ifEmpty { stored?.password.orEmpty() },
        )
        if (login.password.isEmpty()) {
            statusIsGood = false
            status = passwordRequired
            return
        }
        SourceRegistry.duplicateOf(address, exceptId = config.id.takeUnless { isNew })?.let { existing ->
            statusIsGood = false
            status = String.format(alreadyAdded, existing.displayName)
            return
        }
        val candidate = config.copy(baseUrl = address, label = label.trim())
        busy = true
        status = null
        scope.launch {
            val health = withContext(Dispatchers.IO) {
                runCatching { NavidromeSource(candidate, logins = { login }).health() }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            statusIsGood = health.isOk
            status = when (health) {
                is SourceHealth.Ok -> listOfNotNull(connected, health.detail).joinToString(" · ")
                is SourceHealth.Rejected -> health.reason
                is SourceHealth.Unreachable -> health.reason
            }
            busy = false
            if (thenSave) {
                if (!NavidromeCredentials.save(candidate.id, login)) {
                    statusIsGood = false
                    status = storageUnavailable
                    return@launch
                }
                if (isNew) SourceRegistry.add(candidate) else SourceRegistry.update(candidate)
                onSaved()
            }
        }
    }

    ServerEditorAlert(
        hazeState = hazeState,
        title = if (isNew) stringResource(R.string.add_navidrome) else config.displayName,
        description = stringResource(
            if (typedUrl().startsWith("http://", ignoreCase = true)) {
                R.string.navidrome_http_warning
            } else {
                R.string.navidrome_description
            },
        ),
        fields = listOf(
            EditorField(
                value = url,
                onChange = { url = it; status = null },
                placeholder = stringResource(R.string.navidrome_url_placeholder),
                keyboardType = KeyboardType.Uri,
            ),
            EditorField(
                value = username,
                onChange = { username = it; status = null },
                placeholder = stringResource(R.string.username),
            ),
            EditorField(
                value = password,
                onChange = { password = it; status = null },
                placeholder = stringResource(
                    if (stored != null) R.string.navidrome_password_unchanged else R.string.password,
                ),
                isPassword = true,
            ),
            EditorField(
                value = label,
                onChange = { label = it },
                placeholder = stringResource(R.string.navidrome_name_optional),
            ),
        ),
        status = status,
        statusIsGood = statusIsGood,
        testing = busy,
        canSubmit = url.isNotBlank() && username.isNotBlank() && (password.isNotEmpty() || stored != null),
        onTest = { run(thenSave = false) },
        onSave = { run(thenSave = true) },
        removeLabel = stringResource(R.string.remove_source),
        onRemove = if (isNew) null else onDelete,
        onDismiss = onDismiss,
    )
}
