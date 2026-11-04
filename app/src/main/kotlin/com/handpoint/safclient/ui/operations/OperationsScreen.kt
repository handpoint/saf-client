package com.handpoint.safclient.ui.operations

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Choose the operation, then its extra fields. The amount comes next, on the keypad. */
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
            Column(Modifier.padding(16.dp)) {
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = SafTheme.colors.failed.content)
                }
                PrimaryButton(
                    text = "Continue",
                    onClick = onContinue,
                    emphasis = ButtonEmphasis.Accent,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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
            state.kinds.forEach { kind ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onEdit { it.copy(kind = kind) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = form.kind == kind, onClick = null, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                        if (form.kind == kind) {
                            Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = SafTheme.colors.textMuted)
                        }
                    }
                }
            }

            if (form.kind.needsOriginalId) {
                Heading("Original transaction")
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

            if (form.kind.isMoto) {
                Heading("MoTo")
                MotoChannel.entries.forEach { channel ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onEdit { it.copy(motoChannel = channel) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = form.motoChannel == channel, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(channel.label)
                    }
                }
                CheckRow("Ask for the AVS fields", form.avsFields) { onEdit { it.copy(avsFields = !it.avsFields) } }
            }

            if (state.surchargeEnabled && form.kind == OperationKind.MOTO_SALE) {
                CheckRow(
                    "Add the surcharge of Settings (${state.surchargePercent.formatPercent()})",
                    form.useAppSurcharge,
                ) { onEdit { it.copy(useAppSurcharge = !it.useAppSurcharge) } }
            }

            Heading("Extra fields (optional, minor units)")
            Text(
                "Sent as typed. A refund must refuse the surcharge and tax fields (SDKS-467).",
                style = MaterialTheme.typography.bodySmall,
                color = SafTheme.colors.textMuted,
            )
            if (form.kind != OperationKind.SALE) {
                NumberField(form.surcharge, "surchargeAmount") { text -> onEdit { it.copy(surcharge = text) } }
            }
            NumberField(form.legacyTax, "taxAmount (legacy)") { text -> onEdit { it.copy(legacyTax = text) } }
            NumberField(form.level2Tax, "taxInformation.taxAmount") { text -> onEdit { it.copy(level2Tax = text) } }
            CheckRow("taxInformation.taxExempt", form.taxExempt) { onEdit { it.copy(taxExempt = !it.taxExempt) } }
        }
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

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = SafTheme.colors.textMuted,
        modifier = Modifier.padding(top = 12.dp),
    )
}
