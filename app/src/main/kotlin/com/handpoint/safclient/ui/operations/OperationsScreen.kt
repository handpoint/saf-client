package com.handpoint.safclient.ui.operations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handpoint.safclient.R
import com.handpoint.safclient.domain.model.MotoChannel
import com.handpoint.safclient.domain.model.OperationKind
import com.handpoint.safclient.domain.model.formatPercent
import com.handpoint.safclient.ui.components.ButtonEmphasis
import com.handpoint.safclient.ui.components.PrimaryButton
import com.handpoint.safclient.ui.theme.SafTheme

/** The New operation screen, wired to [OperationsViewModel]. [onContinue] goes on to the keypad. */
@Composable
fun OperationsRoute(viewModel: OperationsViewModel, onContinue: (prefillMinor: Long?) -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OperationsScreen(
        state = state,
        onEdit = viewModel::edit,
        onUseLastId = viewModel::useLastTransactionId,
        onContinue = { if (viewModel.proceed()) onContinue(state.prefillMinor) },
        onBack = onBack,
    )
}

/**
 * Choose the operation in a drop-down, then answer what that operation needs (the original
 * transaction, the MoTo channel) and, if wanted, add the surcharge and tax fields. The amount
 * comes next, on the keypad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(
    state: OperationsUiState,
    onEdit: ((OperationForm) -> OperationForm) -> Unit,
    onUseLastId: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    val form = state.form
    Scaffold(
        modifier = modifier,
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.prefillMinor != null) "Refund" else "New operation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = SafTheme.colors.failed.content)
                }
                PrimaryButton(text = "Continue", onClick = onContinue, emphasis = ButtonEmphasis.Positive)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OperationPicker(
                kinds = state.kinds,
                selected = form.kind,
                onSelect = { kind -> onEdit { it.copy(kind = kind) } },
            )

            if (form.kind.needsOriginalId) {
                Section("Original transaction") {
                    OutlinedTextField(
                        value = form.originalTransactionId,
                        onValueChange = { text -> onEdit { it.copy(originalTransactionId = text) } },
                        label = { Text("Original transaction ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onUseLastId, enabled = state.lastTransactionId != null) {
                        Text("Use the last transaction ID" + (state.lastTransactionId?.let { " ($it)" } ?: ""))
                    }
                }
            }

            if (form.kind.isMoto) {
                Section("MoTo") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotoChannel.entries.forEach { channel ->
                            FilterChip(
                                selected = form.motoChannel == channel,
                                onClick = { onEdit { it.copy(motoChannel = channel) } },
                                label = { Text(channel.label) },
                            )
                        }
                    }
                    CheckRow("Ask for the AVS fields", form.avsFields) { onEdit { it.copy(avsFields = !it.avsFields) } }
                    if (state.surchargeEnabled && form.kind == OperationKind.MOTO_SALE) {
                        CheckRow(
                            "Add the surcharge of Settings (${state.surchargePercent.formatPercent()})",
                            form.useAppSurcharge,
                        ) { onEdit { it.copy(useAppSurcharge = !it.useAppSurcharge) } }
                    }
                }
            }

            ExtrasSection(
                form = form,
                showSurcharge = true,
                onEdit = onEdit,
            )
        }
    }
}

/** The drop-down of operations. Each entry shows its name and a line that says what it does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OperationPicker(kinds: List<OperationKind>, selected: OperationKind, onSelect: (OperationKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Operation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText = { Text(selected.hint) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            kinds.forEach { kind ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                            Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = SafTheme.colors.textMuted)
                        }
                    },
                    onClick = {
                        onSelect(kind)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * "Add surcharge or tax fields": off by default. When on, the fields appear and the operation
 * sends them as typed (a refund must refuse them). Blank means not sent.
 */
@Composable
private fun ExtrasSection(form: OperationForm, showSurcharge: Boolean, onEdit: ((OperationForm) -> OperationForm) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add surcharge or tax fields", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (form.kind == OperationKind.SALE) {
                            "Optional. Minor units, sent as typed (a 0 too). The customer still sees the surcharge notice."
                        } else {
                            "Optional. Amounts in minor units, sent as typed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SafTheme.colors.textMuted,
                    )
                }
                Switch(checked = form.useExtras, onCheckedChange = { checked -> onEdit { it.copy(useExtras = checked) } })
            }
            AnimatedVisibility(
                visible = form.useExtras,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showSurcharge) {
                        NumberField(form.surcharge, "surchargeAmount") { text -> onEdit { it.copy(surcharge = text) } }
                    }
                    NumberField(form.legacyTax, "taxAmount (legacy)") { text -> onEdit { it.copy(legacyTax = text) } }
                    NumberField(form.level2Tax, "taxInformation.taxAmount") { text -> onEdit { it.copy(level2Tax = text) } }
                    CheckRow("taxInformation.taxExempt", form.taxExempt) { onEdit { it.copy(taxExempt = !it.taxExempt) } }
                    OutlinedTextField(
                        value = form.purchaseOrder,
                        onValueChange = { text -> onEdit { it.copy(purchaseOrder = text) } },
                        label = { Text("purchaseOrderNumber") },
                        supportingText = { Text("SDK 7.1015: required with taxInformation. Letters and digits, up to 25.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = SafTheme.colors.textMuted)
        content()
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        Text(label)
    }
}

@Composable
private fun NumberField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
