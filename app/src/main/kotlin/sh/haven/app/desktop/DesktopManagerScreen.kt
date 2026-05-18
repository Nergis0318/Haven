package sh.haven.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.Compatibility
import sh.haven.core.local.proot.Distro
import sh.haven.core.local.proot.DistroCatalog
import sh.haven.core.local.proot.PackageFamily
import sh.haven.feature.connections.R

/**
 * Desktop-tab Manage view (issue #162 Phase 3c). Hosts the distro picker
 * + rootfs setup progress + DE install/start/stop rows that used to live
 * in a full-screen dialog behind the Connections topbar. Co-located with
 * the session viewer ([DesktopScreen]) so the install path and the run
 * path share one home — `DesktopScreen` toggles between this view and
 * the session tabs via the TopAppBar Manage action.
 *
 * State and actions are read from [DesktopViewModel]; the three
 * composables below (`DesktopManagerSection`, `DesktopRow`,
 * `DesktopSetupDialog`) are otherwise the same shape they had in
 * `feature/connections/.../ConnectionsScreen.kt` pre-3c — moving here
 * was a relocation, not a rewrite.
 */
@Composable
fun DesktopManagerScreen(viewModel: DesktopViewModel = hiltViewModel()) {
    val installedDesktops = viewModel.installedDesktops
    val desktopStates by viewModel.desktopStates.collectAsState()
    val desktopSetupState by viewModel.desktopSetupState.collectAsState()
    val activeDistroId by viewModel.activeDistroId.collectAsState()
    val rootfsSetupState by viewModel.rootfsSetupState.collectAsState()
    val installedDistros = viewModel.installedDistros
    val availableDistros = viewModel.availableDistros

    var setupDesktopDe by remember {
        mutableStateOf<ProotManager.DesktopEnvironment?>(null)
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DesktopManagerSection(
            installedDesktops = installedDesktops,
            desktopStates = desktopStates,
            desktopSetupState = desktopSetupState,
            activeDistroId = activeDistroId,
            installedDistros = installedDistros,
            availableDistros = availableDistros,
            rootfsSetupState = rootfsSetupState,
            onSwitchDistro = { viewModel.switchActiveDistro(it) },
            onAddDistro = { viewModel.addDistro(it) },
            onDeleteDistro = { viewModel.deleteDistro(it.id) },
            onInstall = { setupDesktopDe = it },
            onStart = { viewModel.startDesktop(it) },
            onStop = { viewModel.stopDesktop(it) },
            onUninstall = { viewModel.uninstallDesktop(it) },
        )
    }

    setupDesktopDe?.let { de ->
        // Auto-dismiss dialog when setup completes — matches the
        // behaviour the Connections-side dialog had pre-3c.
        androidx.compose.runtime.LaunchedEffect(desktopSetupState) {
            if (desktopSetupState is ProotManager.DesktopSetupState.Complete) {
                setupDesktopDe = null
                viewModel.resetDesktopSetupState()
            }
        }
        val activeFamily = DistroCatalog.lookup(activeDistroId)?.family
        DesktopSetupDialog(
            desktopState = desktopSetupState,
            selectedDe = de,
            activeFamily = activeFamily,
            onStart = { password, _, addons ->
                viewModel.setupDesktop(password, de, addons)
            },
            onDismiss = {
                setupDesktopDe = null
                viewModel.resetDesktopSetupState()
            },
        )
    }
}

@Composable
private fun DesktopManagerSection(
    installedDesktops: Set<ProotManager.DesktopEnvironment>,
    desktopStates: Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>,
    desktopSetupState: ProotManager.DesktopSetupState,
    activeDistroId: String,
    installedDistros: List<Distro>,
    availableDistros: List<Distro>,
    rootfsSetupState: ProotManager.SetupState,
    onSwitchDistro: (String) -> Unit,
    onAddDistro: (Distro) -> Unit,
    onDeleteDistro: (Distro) -> Unit,
    onInstall: (ProotManager.DesktopEnvironment) -> Unit,
    onStart: (ProotManager.DesktopEnvironment) -> Unit,
    onStop: (ProotManager.DesktopEnvironment) -> Unit,
    onUninstall: (ProotManager.DesktopEnvironment) -> Unit,
) {
    var distroMenuOpen by remember { mutableStateOf(false) }

    val activeDistroLabel = installedDistros.firstOrNull { it.id == activeDistroId }?.label
        ?: DistroCatalog.lookup(activeDistroId)?.label
        ?: activeDistroId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (installedDistros.size > 1 || availableDistros.isNotEmpty()) {
                Box {
                    AssistChip(
                        onClick = { distroMenuOpen = true },
                        label = { Text(activeDistroLabel) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.DesktopWindows,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = distroMenuOpen,
                        onDismissRequest = { distroMenuOpen = false },
                    ) {
                        installedDistros.forEach { distro ->
                            DropdownMenuItem(
                                text = { Text(distro.label) },
                                onClick = {
                                    onSwitchDistro(distro.id)
                                    distroMenuOpen = false
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (distro.id == activeDistroId) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                distroMenuOpen = false
                                                onDeleteDistro(distro)
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Delete ${distro.label}",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        if (availableDistros.isNotEmpty() && installedDistros.isNotEmpty()) {
                            HorizontalDivider()
                        }
                        availableDistros.forEach { distro ->
                            DropdownMenuItem(
                                text = {
                                    Text("+ ${distro.label}  (~${distro.sizeEstimateMb} MB)")
                                },
                                onClick = {
                                    onAddDistro(distro)
                                    distroMenuOpen = false
                                },
                            )
                        }
                    }
                }
                when (val s = rootfsSetupState) {
                    is ProotManager.SetupState.Downloading -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Downloading rootfs… ${s.progress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    ProotManager.SetupState.Extracting -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Extracting rootfs…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ProotManager.SetupState.Initializing -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Setting up rootfs… (${s.step})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ProotManager.SetupState.Error -> {
                        Spacer(Modifier.height(4.dp))
                        val phaseLabel = when (s.phase) {
                            ProotManager.Phase.RootfsDownload -> "Download"
                            ProotManager.Phase.RootfsExtract -> "Extract"
                            ProotManager.Phase.BootstrapHook -> "Bootstrap hook"
                            ProotManager.Phase.Baseline -> "Baseline packages"
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text("Failed: $phaseLabel", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (s.logTail.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                s.logTail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Full per-phase history: Settings → View PRoot install log.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> { /* Ready / NotInstalled — silent */ }
                }
                Spacer(Modifier.height(8.dp))
            }

            val activeDistro = DistroCatalog.lookup(activeDistroId)
            val compatibleDes = ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { de ->
                    activeDistro == null ||
                        de.spec.packagesPerFamily.containsKey(activeDistro.family)
                }
            compatibleDes.forEach { de ->
                val isInstalled = de in installedDesktops
                val instance = desktopStates[de]
                DesktopRow(
                    de = de,
                    isInstalled = isInstalled,
                    instance = instance,
                    isSetupBusy = desktopSetupState is ProotManager.DesktopSetupState.Installing,
                    activeFamily = activeDistro?.family,
                    onInstall = { onInstall(de) },
                    onStart = { onStart(de) },
                    onStop = { onStop(de) },
                    onUninstall = { onUninstall(de) },
                )
            }
        }
    }
}

@Composable
private fun DesktopRow(
    de: ProotManager.DesktopEnvironment,
    isInstalled: Boolean,
    instance: DesktopManager.DesktopInstance?,
    isSetupBusy: Boolean = false,
    activeFamily: PackageFamily? = null,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    val compatibility = activeFamily?.let { de.spec.compatibilityOn(it) }
        ?: Compatibility.Stable
    var showUninstallConfirm by remember { mutableStateOf(false) }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.connections_desktop_uninstall_title)) },
            text = { Text(stringResource(R.string.connections_desktop_uninstall_message, de.label)) },
            confirmButton = {
                TextButton(onClick = { showUninstallConfirm = false; onUninstall() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            Icons.Filled.Circle,
            contentDescription = null,
            tint = when (instance?.state) {
                DesktopManager.DesktopState.RUNNING -> Color(0xFF4CAF50)
                DesktopManager.DesktopState.STARTING -> Color(0xFFFFC107)
                DesktopManager.DesktopState.ERROR -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(de.label, style = MaterialTheme.typography.bodyMedium)
                if (compatibility == Compatibility.Experimental) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "experimental",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            when {
                instance?.state == DesktopManager.DesktopState.RUNNING && !de.isNative ->
                    Text(
                        "VNC :${instance.displayNumber} (port ${instance.vncPort})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == DesktopManager.DesktopState.RUNNING && de.isNative ->
                    Text(
                        stringResource(R.string.connections_desktop_native_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == DesktopManager.DesktopState.ERROR ->
                    Text(
                        instance.errorMessage ?: "Error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                !isInstalled ->
                    Text(
                        de.sizeEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }

        if (!isInstalled) {
            TextButton(onClick = onInstall, enabled = !isSetupBusy) {
                Text(stringResource(R.string.common_install))
            }
        } else if (isSetupBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            when (instance?.state) {
                DesktopManager.DesktopState.RUNNING ->
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.connections_desktop_stop))
                    }
                DesktopManager.DesktopState.STARTING ->
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> {
                    IconButton(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.connections_desktop_start))
                    }
                    IconButton(onClick = { showUninstallConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopSetupDialog(
    desktopState: ProotManager.DesktopSetupState,
    selectedDe: ProotManager.DesktopEnvironment,
    activeFamily: PackageFamily? = null,
    onStart: (password: String, de: ProotManager.DesktopEnvironment, addons: Set<ProotManager.DesktopAddon>) -> Unit,
    onDismiss: () -> Unit,
) {
    val compatibility = activeFamily?.let { selectedDe.spec.compatibilityOn(it) }
        ?: Compatibility.Stable
    val compatibilityNote = activeFamily?.let { selectedDe.spec.compatibilityNoteOn(it) }
    var password by rememberSaveable { mutableStateOf("haven") }
    var shellCmd by rememberSaveable { mutableStateOf("/bin/sh") }
    var selectedAddons by remember {
        mutableStateOf(emptySet<ProotManager.DesktopAddon>())
    }
    val isInstalling = desktopState is ProotManager.DesktopSetupState.Installing

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text(stringResource(R.string.connections_desktop_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (desktopState) {
                    is ProotManager.DesktopSetupState.Idle -> {
                        Text(
                            "${selectedDe.label} (${selectedDe.sizeEstimate})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (compatibility == Compatibility.Experimental && compatibilityNote != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        "Experimental on ${activeFamily.name}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        compatibilityNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                        Text(
                            when {
                                selectedDe.isWayland -> stringResource(R.string.connections_desktop_wayland_description)
                                selectedDe == ProotManager.DesktopEnvironment.OPENBOX -> stringResource(R.string.connections_desktop_openbox_description)
                                else -> stringResource(R.string.connections_desktop_vnc_description)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!selectedDe.isWayland) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.connections_desktop_vnc_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (selectedDe.isNative) {
                            var shellExpanded by remember { mutableStateOf(false) }
                            val shellOptions = listOf("/bin/sh", "/bin/ash", "/bin/bash", "/bin/zsh", "/bin/fish")
                            ExposedDropdownMenuBox(
                                expanded = shellExpanded,
                                onExpandedChange = { shellExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = shellCmd,
                                    onValueChange = { shellCmd = it },
                                    label = { Text(stringResource(R.string.connections_desktop_shell)) },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shellExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                )
                                ExposedDropdownMenu(
                                    expanded = shellExpanded,
                                    onDismissRequest = { shellExpanded = false },
                                ) {
                                    shellOptions.forEach { shell ->
                                        DropdownMenuItem(
                                            text = { Text(shell) },
                                            onClick = {
                                                shellCmd = shell
                                                shellExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.connections_desktop_addons_header),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            ProotManager.DesktopAddon.entries.forEach { addon ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Checkbox(
                                        checked = addon in selectedAddons,
                                        onCheckedChange = { checked ->
                                            selectedAddons = if (checked) selectedAddons + addon
                                                else selectedAddons - addon
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(addon.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${addon.description} (${addon.sizeEstimate})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is ProotManager.DesktopSetupState.Installing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                desktopState.step,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProotManager.DesktopSetupState.Complete -> {
                        Text(stringResource(R.string.connections_desktop_installed))
                    }
                    is ProotManager.DesktopSetupState.Error -> {
                        val phaseLabel = when (desktopState.phase) {
                            ProotManager.DePhase.Packages -> "Packages"
                            ProotManager.DePhase.VncConfig -> "VNC config"
                            ProotManager.DePhase.Marker -> "Marker file"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Failed: $phaseLabel", style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            )
                            Text(
                                stringResource(R.string.connections_desktop_setup_failed, desktopState.message),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (desktopState.logTail.isNotEmpty()) {
                                Text(
                                    desktopState.logTail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 10,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                "Full per-phase history: Settings → View PRoot install log.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (desktopState is ProotManager.DesktopSetupState.Idle) {
                TextButton(
                    onClick = {
                        onStart(password, selectedDe, selectedAddons)
                    },
                ) { Text(stringResource(R.string.common_install)) }
            }
        },
        dismissButton = {
            if (!isInstalling) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
