package com.codex.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.AppServerMode
import com.codex.remote.domain.ConnectionDraft
import com.codex.remote.domain.ConnectionDraftIssue
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import com.codex.remote.domain.validationIssues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SavedConnection?) -> Unit,
    onDelete: (SavedConnection) -> Unit,
    onConnect: (SavedConnection) -> Unit,
    onSave: (ConnectionDraft, Boolean) -> Unit,
    onUpdateDraft: (ConnectionDraft) -> Unit,
    onCloseEditor: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<SavedConnection?>(null) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            onDismissNotice()
        }
    }

    if (state.showConnectionEditor && state.connectionDraft != null) {
        ConnectionEditor(
            original = state.editingConnection,
            draft = state.connectionDraft,
            busy = state.isBusy,
            snackbarHostState = snackbarHostState,
            onDraftChange = onUpdateDraft,
            onDismiss = onCloseEditor,
            onSave = onSave,
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Remote hosts") },
                navigationIcon = {
                    if (state.activeConnection != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to workspace")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add connection")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.savedConnections.isEmpty()) {
                EmptyConnections(onAdd)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("SSH HOSTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.savedConnections.forEach { connection ->
                        ConnectionRow(
                            connection = connection,
                            isActive = state.activeConnection?.id == connection.id,
                            isConnecting = state.activeConnection?.id == connection.id && state.connectionStatus == ConnectionStatus.CONNECTING,
                            onConnect = { onConnect(connection) },
                            onEdit = { onEdit(connection) },
                            onDelete = { pendingDelete = connection },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Credentials are encrypted with Android Keystore. Confirmed host fingerprints are saved on first connection. Connections are blocked if the host key changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }


    pendingDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${connection.name}?") },
            text = { Text("The saved host and its encrypted credentials will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(connection)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyConnections(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().widthIn(max = 420.dp).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Connect to a remote host", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Codex runs on the remote host. Projects and conversations are imported automatically after connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add SSH host")
        }
    }
}

@Composable
private fun ConnectionRow(
    connection: SavedConnection,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Router, contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = "Current host",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Text(
                    "${connection.username}@${connection.host}:${connection.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "All Codex projects and conversations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onConnect) { Text(if (isActive) "Reconnect" else "Connect") }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConnectionEditor(
    original: SavedConnection?,
    draft: ConnectionDraft,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onDraftChange: (ConnectionDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ConnectionDraft, Boolean) -> Unit,
) {
    var attemptedSave by rememberSaveable(original?.id) { mutableStateOf(false) }
    val validationIssues = draft.validationIssues(original)
    val validationIssue = validationIssues.firstOrNull()
    val submit: (Boolean) -> Unit = { connectAfterSave ->
        attemptedSave = true
        if (validationIssues.isEmpty()) onSave(draft, connectAfterSave)
    }
    BackHandler { if (!busy) onDismiss() }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "Add SSH host" else "Edit SSH host") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionLabel("CONNECTION")
                OutlinedTextField(
                    enabled = !busy,
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = attemptedSave && ConnectionDraftIssue.CONNECTION_NAME in validationIssues,
                    supportingText = if (attemptedSave && ConnectionDraftIssue.CONNECTION_NAME in validationIssues) {
                        { Text("Required") }
                    } else null,
                )
                Text(
                    "Projects and conversations are discovered from the remote Codex history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionLabel("SSH HOST")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        enabled = !busy,
                        value = draft.host,
                        onValueChange = { onDraftChange(draft.copy(host = it)) },
                        label = { Text("Host") },
                        placeholder = { Text("devbox.example.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        isError = attemptedSave && ConnectionDraftIssue.HOST in validationIssues,
                        supportingText = if (attemptedSave && ConnectionDraftIssue.HOST in validationIssues) {
                            { Text("Required") }
                        } else null,
                    )
                    OutlinedTextField(
                        enabled = !busy,
                        value = draft.port,
                        onValueChange = { onDraftChange(draft.copy(port = it.filter(Char::isDigit))) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(104.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = attemptedSave && ConnectionDraftIssue.PORT in validationIssues,
                        supportingText = if (attemptedSave && ConnectionDraftIssue.PORT in validationIssues) {
                            { Text("1-65535") }
                        } else null,
                    )
                }
                OutlinedTextField(
                    enabled = !busy,
                    value = draft.username,
                    onValueChange = { onDraftChange(draft.copy(username = it)) },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = attemptedSave && ConnectionDraftIssue.USERNAME in validationIssues,
                    supportingText = if (attemptedSave && ConnectionDraftIssue.USERNAME in validationIssues) {
                        { Text("Required") }
                    } else null,
                )
                SectionLabel("AUTHENTICATION")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        enabled = !busy,
                        selected = draft.authType == AuthType.PASSWORD,
                        onClick = { onDraftChange(draft.copy(authType = AuthType.PASSWORD)) },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        enabled = !busy,
                        selected = draft.authType == AuthType.PRIVATE_KEY,
                        onClick = { onDraftChange(draft.copy(authType = AuthType.PRIVATE_KEY)) },
                        label = { Text("Private key") },
                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
                if (draft.authType == AuthType.PASSWORD) {
                    OutlinedTextField(
                        enabled = !busy,
                        value = draft.password,
                        onValueChange = { onDraftChange(draft.copy(password = it)) },
                        label = { Text(if (original == null) "Password" else "Password (leave blank to keep)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = attemptedSave && ConnectionDraftIssue.PASSWORD in validationIssues,
                        supportingText = if (attemptedSave && ConnectionDraftIssue.PASSWORD in validationIssues) {
                            { Text("Required") }
                        } else null,
                    )
                } else {
                    OutlinedTextField(
                        enabled = !busy,
                        value = draft.privateKey,
                        onValueChange = { onDraftChange(draft.copy(privateKey = it)) },
                        label = { Text(if (original == null) "OpenSSH / PEM private key" else "Private key (leave blank to keep)") },
                        minLines = 5,
                        maxLines = 9,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = attemptedSave && ConnectionDraftIssue.PRIVATE_KEY in validationIssues,
                        supportingText = if (attemptedSave && ConnectionDraftIssue.PRIVATE_KEY in validationIssues) {
                            { Text("Required") }
                        } else null,
                    )
                    OutlinedTextField(
                        enabled = !busy,
                        value = draft.passphrase,
                        onValueChange = { onDraftChange(draft.copy(passphrase = it)) },
                        label = { Text("Key passphrase (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SectionLabel("REMOTE PLATFORM")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemotePlatform.entries.forEach { platform ->
                        FilterChip(
                            enabled = !busy,
                            selected = draft.platform == platform,
                            onClick = { onDraftChange(draft.copy(platform = platform)) },
                            label = { Text(platform.displayName) },
                        )
                    }
                }
                SectionLabel("APP SERVER")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        enabled = !busy,
                        selected = draft.appServerMode == AppServerMode.SESSION,
                        onClick = { onDraftChange(draft.copy(appServerMode = AppServerMode.SESSION)) },
                        label = { Text("Per connection") },
                    )
                    FilterChip(
                        enabled = !busy,
                        selected = draft.appServerMode == AppServerMode.DAEMON,
                        onClick = { onDraftChange(draft.copy(appServerMode = AppServerMode.DAEMON)) },
                        label = { Text("Background daemon") },
                    )
                }
                Text(
                    if (draft.appServerMode == AppServerMode.DAEMON) {
                        "Starts or reuses the host's shared Codex daemon. It keeps running after you disconnect. Requires a Codex CLI with daemon and proxy support."
                    } else {
                        "Starts a separate app server for this SSH connection."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.hostKeyFingerprint.isNotBlank()) {
                    SectionLabel("HOST KEY")
                    OutlinedTextField(
                        enabled = !busy,
                        value = draft.hostKeyFingerprint,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Pinned fingerprint") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(enabled = !busy, onClick = {
                                onDraftChange(draft.copy(hostKeyFingerprint = "", clearHostKeyFingerprint = true))
                            }) { Text("Clear") }
                        },
                    )
                }
                HorizontalDivider()
                if (attemptedSave && validationIssue != null) {
                    Text(
                        validationIssue.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    OutlinedButton(onClick = { submit(false) }, enabled = !busy) { Text("Save") }
                    Button(onClick = { submit(true) }, enabled = !busy) { Text("Save & connect") }
                }
            }
        }
    }
}

private val ConnectionDraftIssue.message: String
    get() = when (this) {
        ConnectionDraftIssue.CONNECTION_NAME -> "Display name is required."
        ConnectionDraftIssue.HOST -> "SSH host is required."
        ConnectionDraftIssue.PORT -> "Port must be between 1 and 65535."
        ConnectionDraftIssue.USERNAME -> "Username is required."
        ConnectionDraftIssue.PASSWORD -> "Password is required."
        ConnectionDraftIssue.PRIVATE_KEY -> "Paste an OpenSSH or PEM private key."
    }

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val RemotePlatform.displayName: String
    get() = when (this) {
        RemotePlatform.AUTO -> "Auto"
        RemotePlatform.POSIX -> "Linux / macOS"
        RemotePlatform.WINDOWS -> "Windows"
    }
