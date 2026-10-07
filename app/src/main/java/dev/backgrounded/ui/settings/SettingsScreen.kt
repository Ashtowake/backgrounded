package dev.backgrounded.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.backgrounded.BuildConfig
import dev.backgrounded.R
import dev.backgrounded.core.security.DeviceCredentialGate
import dev.backgrounded.core.security.SystemAuthentication
import dev.backgrounded.core.wallpaper.LiveWallpaperController
import dev.backgrounded.data.backup.ImageRecoveryResult
import dev.backgrounded.domain.model.DoubleTapMode
import dev.backgrounded.domain.model.GestureAction
import dev.backgrounded.ui.components.LabelValueRow
import dev.backgrounded.ui.components.SectionTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val missingImages by viewModel.missingImages.collectAsStateWithLifecycle()
    val recoveringImages by viewModel.recoveringImages.collectAsStateWithLifecycle()
    val managedFolders by viewModel.managedFolders.collectAsStateWithLifecycle()
    val unresolvedFiles by viewModel.unresolvedFiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingEncryption by remember { mutableStateOf<Boolean?>(null) }
    var pendingPinSetup by remember { mutableStateOf(false) }
    var pendingSystemSetup by remember { mutableStateOf(false) }
    var pendingPinRemoval by remember { mutableStateOf(false) }
    var pendingHideSources by remember { mutableStateOf(false) }
    var pinValue by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinRecovery by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }
    var fullAccessGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        fullAccessGranted = Environment.isExternalStorageManager()
        onPauseOrDispose { }
    }
    val authenticateSystem: (String, (Boolean) -> Unit) -> Unit = { title, onResult ->
        if (viewModel.prepareSystemKey()) {
            SystemAuthentication.authenticate(context, title, onResult)
        } else {
            scope.launch { snackbarHostState.showSnackbar("Could not prepare system authentication") }
            onResult(false)
        }
    }
    val pinCredentialLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            scope.launch {
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val enabled = pendingEncryption
                    val authenticated =
                        if (viewModel.pinConfigured()) {
                            viewModel.recoverPin()
                        } else {
                            viewModel.setupPin(pinValue, true)
                        }
                    if (authenticated && enabled == null) {
                        pendingPinSetup = false
                        pinValue = ""
                        pinConfirm = ""
                        if (pendingSystemSetup) {
                            pendingSystemSetup = false
                            authenticateSystem("Enable system authentication") { success ->
                                if (success && !viewModel.unlockWithSystem()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Could not enable system authentication",
                                        )
                                    }
                                }
                            }
                        }
                    } else if (enabled != null && authenticated) {
                        viewModel.setEncryptHidden(enabled) { success ->
                            if (success) {
                                pendingEncryption = null
                                pinValue = ""
                                pinConfirm = ""
                            } else {
                                pinError = true
                            }
                        }
                    } else {
                        pinError = true
                    }
                }
            }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let {
                viewModel.exportTo(it) { success ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(if (success) R.string.export_finished else R.string.import_failed),
                        )
                    }
                }
            }
        }

    val diagnosticsExportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let {
                viewModel.exportDiagnostics(it) { success ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (success) "Diagnostics exported" else "Export failed",
                        )
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                viewModel.importFrom(it) { success ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (success && viewModel.missingImages.value > 0) {
                                "Configuration imported; ${viewModel.missingImages.value} images missing. " +
                                    "Choose an original folder to restore them."
                            } else {
                                context.getString(if (success) R.string.import_finished else R.string.import_failed)
                            },
                        )
                    }
                }
            }
        }

    val reportRecovery: (ImageRecoveryResult) -> Unit = { result ->
        scope.launch {
            snackbarHostState.showSnackbar("${result.restored} images restored; ${result.missing} still missing")
        }
    }
    val restoreFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                viewModel.restoreImages(uri, reportRecovery)
            }
        }

    val grantFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && !viewModel.grantFolder(uri)) {
                scope.launch { snackbarHostState.showSnackbar("Folder write access was not granted") }
            }
        }
    val fullAccessLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            fullAccessGranted = Environment.isExternalStorageManager()
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = {
                if (!LiveWallpaperController.launchApply(context)) {
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.wallpaper_picker_unavailable))
                    }
                }
            }) {
                Text(stringResource(R.string.set_live_wallpaper))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.rotationPaused, onCheckedChange = { viewModel.togglePause() })
                Text(
                    text = stringResource(R.string.pause),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionTitle(stringResource(R.string.double_tap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.doubleTapEnabled,
                    onCheckedChange = viewModel::setDoubleTapEnabled,
                )
                Text(
                    text = stringResource(R.string.double_tap_enabled),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (settings.doubleTapEnabled) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.doubleTapMode == DoubleTapMode.BACKGROUND,
                        onClick = { viewModel.setDoubleTapMode(DoubleTapMode.BACKGROUND) },
                        label = { Text(stringResource(R.string.mode_background)) },
                    )
                    FilterChip(
                        selected = settings.doubleTapMode == DoubleTapMode.ANYWHERE,
                        onClick = { viewModel.setDoubleTapMode(DoubleTapMode.ANYWHERE) },
                        label = { Text(stringResource(R.string.mode_anywhere)) },
                    )
                }
                Text(
                    text = stringResource(R.string.double_tap_action),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GestureAction.entries.forEach { action ->
                        FilterChip(
                            selected = settings.doubleTapAction == action,
                            onClick = { viewModel.setDoubleTapAction(action) },
                            label = { Text(stringResource(actionLabel(action))) },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.lockDimDefault,
                    onCheckedChange = viewModel::setLockDimDefault,
                )
                Text(
                    text = stringResource(R.string.lock_dim_default),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionTitle("Rendering and folder scans")
            Text("Animation ceiling")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { fps ->
                    FilterChip(
                        selected = settings.animationFps == fps,
                        onClick = { viewModel.setAnimationFps(fps) },
                        label = { Text("$fps FPS") },
                    )
                }
            }
            Text("Folder scan interval")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Every rotation", 60 to "1 minute", 300 to "5 minutes", 900 to "15 minutes").forEach {
                        (seconds, label) ->
                    FilterChip(
                        selected = settings.folderScanSeconds == seconds,
                        onClick = { viewModel.setFolderScanSeconds(seconds) },
                        label = { Text(label) },
                    )
                }
            }
            Text("Shorter intervals increase battery consumption.")

            SectionTitle(stringResource(R.string.history))
            TextButton(onClick = onOpenHistory) {
                Text(stringResource(R.string.history))
            }

            SectionTitle(stringResource(R.string.export_config))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("backgrounded-backup.json") }) {
                    Text(stringResource(R.string.export_config))
                }
                Button(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text(stringResource(R.string.import_config))
                }
            }

            Text("Configuration exports contain editing settings and file references, not image files.")
            Text("Keep a separate copy of the originals before uninstalling or changing devices.")
            if (missingImages > 0) {
                Text("$missingImages images are missing. Restore originals by matching their content hashes.")
                if (settings.encryptHidden) Text("Unhide encrypted albums before restoring missing images.")
                Button(
                    enabled = !recoveringImages,
                    onClick = { restoreFolderLauncher.launch(null) },
                ) { Text("Restore images from folder") }
                if (fullAccessGranted || managedFolders.isNotEmpty()) {
                    TextButton(
                        enabled = !recoveringImages,
                        onClick = { viewModel.restoreImages(onResult = reportRecovery) },
                    ) { Text("Scan granted locations") }
                }
                if (recoveringImages) Text("Matching original images...")
            }

            SectionTitle("Hidden album access")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.authenticateHiddenSwitch,
                    onCheckedChange = viewModel::setAuthenticateHiddenSwitch,
                )
                Text("Authenticate when switching to a hidden album", modifier = Modifier.padding(start = 8.dp))
            }
            if (!viewModel.pinConfigured()) {
                TextButton(onClick = { pendingPinSetup = true }) { Text("Add optional PIN") }
            }
            if (viewModel.pinConfigured() && viewModel.systemConfigured()) {
                TextButton(onClick = { pendingPinRemoval = true }) { Text("Remove optional PIN") }
            }
            if (SystemAuthentication.available(context) && !viewModel.systemConfigured()) {
                TextButton(onClick = {
                    if (viewModel.pinConfigured() && !viewModel.pinUnlocked()) {
                        pendingSystemSetup = true
                    } else {
                        authenticateSystem("Enable system authentication") { authenticated ->
                            if (authenticated && !viewModel.unlockWithSystem()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Could not enable system authentication",
                                    )
                                }
                            }
                        }
                    }
                }) { Text("Use fingerprint or device credential") }
            }
            SectionTitle("File access")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { grantFolderLauncher.launch(null) }) { Text("Grant folder") }
                Button(onClick = {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${context.packageName}"))
                    fullAccessLauncher.launch(intent)
                }) { Text(if (fullAccessGranted) "Full access granted" else "Grant full access") }
            }
            Text("Full access covers local shared files. Folder access also supports selected document providers.")
            managedFolders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (Uri.parse(folder).lastPathSegment ?: folder) +
                            if (viewModel.hasFolderWrite(Uri.parse(folder))) "" else " (grant expired)",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.revokeFolder(Uri.parse(folder)) }) { Text("Revoke") }
                }
            }
            unresolvedFiles.forEach { file ->
                Text(
                    "Source needs attention: ${file.originalName}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (unresolvedFiles.isNotEmpty()) {
                TextButton(onClick = {
                    viewModel.retryRestoration { remaining ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (remaining.isEmpty()) {
                                    "Restoration checked"
                                } else {
                                    "Could not restore: ${remaining.joinToString()}"
                                },
                            )
                        }
                    }
                }) { Text("Retry restoration for visible albums") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.hideSourcesSystemwide, onCheckedChange = {
                    if (it) pendingHideSources = true else viewModel.setHideSources(false)
                })
                Text("Move verified originals for hidden albums", modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.encryptHidden, onCheckedChange = { enabled ->
                    if (SystemAuthentication.available(context) &&
                        (viewModel.systemConfigured() || !viewModel.pinConfigured())
                    ) {
                        authenticateSystem("Hidden images") { authenticated ->
                            if (authenticated && viewModel.unlockWithSystem()) {
                                viewModel.setEncryptHidden(enabled) { success ->
                                    if (!success) {
                                        scope.launch { snackbarHostState.showSnackbar("Encryption change failed") }
                                    }
                                }
                            } else if (authenticated) {
                                pendingEncryption = enabled
                            }
                        }
                    } else {
                        pendingEncryption = enabled
                    }
                })
                Text("Encrypt hidden images", modifier = Modifier.padding(start = 8.dp))
            }
            if (BuildConfig.DEBUG) {
                SectionTitle("Developer diagnostics")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = settings.debugDiagnostics, onCheckedChange = viewModel::setDebugDiagnostics)
                    Text("Record local debug events", modifier = Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { diagnosticsExportLauncher.launch("backgrounded-debug.log") }) {
                    Text("Export diagnostics")
                }
            }

            SectionTitle(stringResource(R.string.external_control))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.externalControlEnabled,
                    onCheckedChange = viewModel::setExternalControl,
                )
                Text(
                    text = stringResource(R.string.external_control),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.external_control_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.about))
            val version =
                remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull().orEmpty()
                }
            LabelValueRow(
                label = stringResource(R.string.version),
                value = version,
                modifier = Modifier.fillMaxWidth(),
            )
            LabelValueRow(
                label = stringResource(R.string.license),
                value = stringResource(R.string.license_value),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.permissions_none),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (pendingEncryption != null || pendingPinSetup || pendingSystemSetup) {
        val enabled = pendingEncryption == true
        AlertDialog(
            onDismissRequest = {
                pendingEncryption = null
                pendingPinSetup = false
                pendingSystemSetup = false
            },
            title = {
                Text(
                    if (pendingPinSetup) {
                        "Set hidden-album PIN"
                    } else if (pendingSystemSetup) {
                        "Unlock hidden images"
                    } else if (enabled) {
                        "Encrypt hidden images"
                    } else {
                        "Decrypt hidden images"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (pendingPinSetup) {
                            "Set a PIN of at least six digits."
                        } else if (pendingSystemSetup) {
                            "Enter the existing PIN once to enable system authentication."
                        } else {
                            "Enter your hidden-album PIN to change encryption."
                        },
                    )
                    OutlinedTextField(
                        value = pinValue,
                        onValueChange = {
                            pinValue = it.filter(Char::isDigit).take(32)
                            pinError = false
                        },
                        label = { Text("PIN (at least 6 digits)") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                            ),
                    )
                    if (!viewModel.pinConfigured()) {
                        OutlinedTextField(
                            value = pinConfirm,
                            onValueChange = { pinConfirm = it.filter(Char::isDigit).take(32) },
                            label = { Text("Confirm PIN") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = pinRecovery, onCheckedChange = { pinRecovery = it })
                            Text("Allow device-credential recovery")
                        }
                    }
                    if (pinError) Text("PIN incorrect or encryption failed", color = MaterialTheme.colorScheme.error)
                    if (viewModel.pinConfigured() && viewModel.pinRecoveryEnabled()) {
                        TextButton(onClick = {
                            DeviceCredentialGate.confirmIntent(context, "Recover hidden albums")
                                ?.let(pinCredentialLauncher::launch)
                        }) { Text("Use device credential") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val authenticated =
                            if (viewModel.pinConfigured()) {
                                viewModel.unlockPin(pinValue)
                            } else if (pinValue == pinConfirm) {
                                if (pinRecovery) {
                                    val intent = DeviceCredentialGate.confirmIntent(context, "Enable PIN recovery")
                                    if (intent != null) pinCredentialLauncher.launch(intent) else pinError = true
                                    false
                                } else {
                                    viewModel.setupPin(pinValue, false)
                                }
                            } else {
                                false
                            }
                        if (authenticated) {
                            if (pendingPinSetup) {
                                pendingPinSetup = false
                                pinValue = ""
                                pinConfirm = ""
                            } else if (pendingSystemSetup) {
                                pendingSystemSetup = false
                                pinValue = ""
                                authenticateSystem("Enable system authentication") { success ->
                                    if (success && !viewModel.unlockWithSystem()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Could not enable system authentication",
                                            )
                                        }
                                    }
                                }
                            } else {
                                viewModel.setEncryptHidden(enabled) { success ->
                                    if (success) {
                                        pendingEncryption = null
                                        pinValue = ""
                                        pinConfirm = ""
                                    } else {
                                        pinError = true
                                    }
                                }
                            }
                        } else if (!pinRecovery || viewModel.pinConfigured()) {
                            pinError = true
                        }
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingEncryption = null
                    pendingPinSetup = false
                    pendingSystemSetup = false
                }) {
                    Text("Cancel")
                }
            },
        )
    }
    if (pendingPinRemoval) {
        AlertDialog(
            onDismissRequest = { pendingPinRemoval = false },
            title = { Text("Remove optional PIN") },
            text = { Text("Hidden images will open with fingerprint or device credential.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPinRemoval = false
                    authenticateSystem("Remove optional PIN") { authenticated ->
                        if (authenticated && (!viewModel.unlockWithSystem() || !viewModel.removePin())) {
                            scope.launch { snackbarHostState.showSnackbar("Could not remove PIN") }
                        }
                    }
                }) { Text("Remove PIN") }
            },
            dismissButton = { TextButton(onClick = { pendingPinRemoval = false }) { Text("Cancel") } },
        )
    }
    if (pendingHideSources) {
        AlertDialog(
            onDismissRequest = { pendingHideSources = false },
            title = { Text("Move source photos") },
            text = {
                Text(
                    "New photos in hidden linked folders are copied, verified, then removed from the " +
                        "selected folder. When hiding an existing album, choose its source folder or full access. " +
                        "For an already hidden album, reveal it and choose Move original photos. " +
                        "Only exact matches in selected folders or local shared storage are moved. " +
                        "Other apps and cloud services may retain copies.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setHideSources(true)
                    pendingHideSources = false
                }) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { pendingHideSources = false }) { Text("Cancel") } },
        )
    }
}

private fun actionLabel(action: GestureAction): Int =
    when (action) {
        GestureAction.NEXT -> R.string.action_next
        GestureAction.PREVIOUS -> R.string.action_previous
        GestureAction.NEXT_ALBUM -> R.string.action_next_album
        GestureAction.TOGGLE_PAUSE -> R.string.action_toggle_pause
        GestureAction.OPEN_APP -> R.string.action_open_app
    }
