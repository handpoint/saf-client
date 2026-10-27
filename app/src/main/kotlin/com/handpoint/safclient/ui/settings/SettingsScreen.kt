package com.handpoint.safclient.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handpoint.safclient.BuildConfig
import com.handpoint.safclient.R
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.domain.model.formatPercent
import com.handpoint.safclient.ui.components.ConnectionChip
import com.handpoint.safclient.ui.settings.SettingsViewModel.Companion.MERCHANT_NAME_MAX
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import kotlinx.coroutines.launch
import java.util.Currency
import java.util.Locale

/** Handpoint's Store and Forward guide for partners. */
const val SAF_GUIDE_URL = "https://claude.ai/artifact/LQU8XW5VWfSsimEnj8c5pX"

/** What the merchant can do on the settings screen. */
class SettingsActions(
    val onBack: () -> Unit,
    /** Returns `false` when the name is not accepted (blank). */
    val onSaveMerchantName: (String) -> Boolean,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onOpenGuide: () -> Unit,
    val onSetSurchargeEnabled: (Boolean) -> Unit = {},
    /** Returns `false` when the text is not an accepted percent. */
    val onSaveSurchargePercent: (String) -> Boolean = { true },
)

/** The settings screen, wired to [SettingsViewModel]. */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SettingsScreen(
        state = state,
        sdkVersion = BuildConfig.SDK_VERSION,
        appVersion = BuildConfig.VERSION_NAME,
        snackbar = snackbar,
        actions = SettingsActions(
            onBack = onBack,
            onSaveMerchantName = viewModel::saveMerchantName,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onSetSurchargeEnabled = viewModel::setSurchargeEnabled,
            onSaveSurchargePercent = viewModel::saveSurchargePercent,
            onOpenGuide = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SAF_GUIDE_URL.toUri()))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar("No browser found to open the guide.") }
                }
            },
        ),
    )
}

/** Store details, the terminal's Store and Forward setup, and where to learn more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    sdkVersion: String,
    appVersion: String,
    snackbar: SnackbarHostState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    var editingName by rememberSaveable { mutableStateOf(false) }
    var editingPercent by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Store") {
                ListItem(
                    overlineContent = { Text("Merchant name") },
                    headlineContent = { Text(state.merchantName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text("Edit", color = SafTheme.colors.accent) },
                    colors = itemColors(),
                    modifier = Modifier.clickable(
                        onClickLabel = "Edit merchant name",
                        role = Role.Button,
                    ) { editingName = true },
                )
            }
            Section("Store and Forward") {
                ListItem(
                    overlineContent = { Text("Currency") },
                    headlineContent = { Text(state.currency?.describe() ?: "—") },
                    supportingContent = { Text("Set by Handpoint on the terminal") },
                    colors = itemColors(),
                )
                Divider()
                ListItem(
                    overlineContent = { Text("Terminal") },
                    headlineContent = { ConnectionChip(state.connection) },
                    trailingContent = { TerminalAction(state, actions) },
                    colors = itemColors(),
                )
            }
            Section("Surcharge") {
                ListItem(
                    headlineContent = { Text("Ask for a surcharge") },
                    supportingContent = { Text("Shows the surcharge notice before the card is read") },
                    trailingContent = {
                        Switch(checked = state.surchargeEnabled, onCheckedChange = actions.onSetSurchargeEnabled)
                    },
                    colors = itemColors(),
                )
                Divider()
                ListItem(
                    overlineContent = { Text("Percent of the sale amount") },
                    headlineContent = { Text(state.surchargePercent.formatPercent()) },
                    supportingContent = {
                        if (state.surchargePercent > SettingsViewModel.SURCHARGE_CAP_PERCENT) {
                            Text(
                                "Above 3%, the usual US limit. The gateway can refuse it.",
                                color = SafTheme.colors.stored.content,
                            )
                        }
                    },
                    trailingContent = { Text("Edit", color = SafTheme.colors.accent) },
                    colors = itemColors(),
                    modifier = Modifier.clickable(
                        onClickLabel = "Edit surcharge percent",
                        role = Role.Button,
                    ) { editingPercent = true },
                )
            }
            Section("About") {
                ListItem(
                    headlineContent = { Text("SDK version") },
                    trailingContent = { Text(sdkVersion) },
                    colors = itemColors(),
                )
                Divider()
                ListItem(
                    headlineContent = { Text("App version") },
                    trailingContent = { Text(appVersion) },
                    colors = itemColors(),
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Store and Forward guide") },
                    supportingContent = { Text("Opens in your browser") },
                    colors = itemColors(),
                    modifier = Modifier.clickable(role = Role.Button, onClick = actions.onOpenGuide),
                )
            }
        }
    }

    if (editingPercent) {
        SurchargePercentDialog(
            initial = state.surchargePercent.stripTrailingZeros().toPlainString(),
            onSave = actions.onSaveSurchargePercent,
            onDismiss = { editingPercent = false },
        )
    }

    if (editingName) {
        MerchantNameDialog(
            initial = state.merchantName,
            onSave = actions.onSaveMerchantName,
            onDismiss = { editingName = false },
        )
    }
}

@Composable
private fun TerminalAction(state: SettingsUiState, actions: SettingsActions) {
    when (state.connection) {
        TerminalConnection.CONNECTED -> TextButton(onClick = actions.onDisconnect) { Text("Disconnect") }
        TerminalConnection.CONNECTING -> Unit // the chip already says so
        TerminalConnection.DISCONNECTED, TerminalConnection.NOT_CONFIGURED ->
            TextButton(onClick = actions.onConnect, enabled = !state.connecting) { Text("Connect") }
    }
}

@Composable
private fun MerchantNameDialog(initial: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.take(MERCHANT_NAME_MAX)) }
    val save = { if (onSave(name)) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merchant name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MERCHANT_NAME_MAX) },
                singleLine = true,
                label = { Text("Shown on Home") },
                supportingText = { Text("${name.length} / $MERCHANT_NAME_MAX") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = save, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SurchargePercentDialog(initial: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = SettingsViewModel.parseSurchargePercent(text) != null
    val save = { if (onSave(text)) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Surcharge percent") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(6) },
                singleLine = true,
                label = { Text("Percent") },
                suffix = { Text("%") },
                isError = text.isNotBlank() && !valid,
                supportingText = { Text("Above 0, up to 100, two decimals at most") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = save, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = SafTheme.colors.textMuted,
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = SafTheme.colors.hairline)

@Composable
private fun itemColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    overlineColor = SafTheme.colors.textMuted,
    supportingColor = SafTheme.colors.textMuted,
)

/** "USD — US Dollar". */
private fun Currency.describe(): String = "$currencyCode — ${getDisplayName(Locale.getDefault())}"

@SafPreviews
@Composable
private fun SettingsScreenPreview() = SafTheme {
    SettingsScreen(
        state = SettingsUiState(
            merchantName = "Café Aurora",
            currency = Currency.getInstance("USD"),
            connection = TerminalConnection.CONNECTED,
        ),
        sdkVersion = "7.1014.1",
        appVersion = "1.0",
        snackbar = remember { SnackbarHostState() },
        actions = SettingsActions({}, { true }, {}, {}, {}),
    )
}
