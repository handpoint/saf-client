package com.handpoint.safclient.ui.detail

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.handpoint.safclient.R
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.ui.components.AmountText
import com.handpoint.safclient.ui.components.EmptyState
import com.handpoint.safclient.ui.components.SecondaryButton
import com.handpoint.safclient.ui.components.StatusPill
import com.handpoint.safclient.ui.components.label
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** The sale detail screen, wired to [SaleDetailViewModel]. [onBack] leaves it (also after a delete). */
@Composable
fun SaleDetailRoute(viewModel: SaleDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // A delete that ends while the app is in the background waits here until the screen is back.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.deleted.collect { onBack() } }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.onMessageShown()
    }

    SaleDetailScreen(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onRefresh = viewModel::refreshStatus,
        onDelete = viewModel::deleteSale,
    )
}

/**
 * One sale: the amount, its way from the terminal to Handpoint, card details, and the
 * reference to copy. Refresh status asks Handpoint again; Delete is offered only while the
 * sale is still stored on the terminal (it has not been charged).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleDetailScreen(
    state: SaleDetailUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val sale = state.sale

    Scaffold(
        modifier = modifier,
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sale") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    if (sale != null) StatusPill(sale.status, Modifier.padding(end = 16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
            )
        },
        bottomBar = {
            if (sale != null && (state.canRefresh || state.canDelete)) {
                // With edge-to-edge, a custom bottom bar must keep clear of the navigation bar.
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.canRefresh) {
                        SecondaryButton(
                            text = "Refresh status",
                            onClick = onRefresh,
                            loading = state.refreshing,
                            enabled = state.storeAndForwardActive,
                        )
                        if (!state.storeAndForwardActive) {
                            Text(
                                text = "Available when the app is in offline mode",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SafTheme.colors.textMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (state.canDelete) {
                        TextButton(
                            onClick = { confirmingDelete = true },
                            enabled = !state.deleting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = SafTheme.colors.failed.content),
                        ) {
                            Text("Delete sale", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            sale != null -> SaleDetails(sale, state.isUnconfirmed, snackbar, Modifier.padding(padding))
            state.notFound -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "Sale not found",
                    body = "This sale is no longer on this device.",
                    action = { TextButton(onClick = onBack) { Text("Back") } },
                )
            }
            else -> Unit // loading: the journal answers almost at once
        }
    }

    if (confirmingDelete && state.canDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this sale?") },
            text = { Text("It hasn't been charged and will be removed from the terminal.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = SafTheme.colors.failed.content),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SaleDetails(sale: Sale, unconfirmed: Boolean, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .semantics { heading() },
            contentAlignment = Alignment.Center,
        ) {
            AmountText(sale.amount, SafTheme.amounts.hero)
        }
        if (unconfirmed) UnconfirmedNotice()
        Timeline(sale.timeline())
        InfoCard(sale, snackbar)
        Spacer(Modifier.height(8.dp))
    }
}

/** An online sale the app could not confirm: say what to do, before anything else. */
@Composable
private fun UnconfirmedNotice() {
    val tone = SafTheme.colors.stored
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = tone.container,
        contentColor = tone.content,
    ) {
        Text(
            text = "We couldn't confirm this sale. Check with Handpoint before charging the customer again.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** One step of the sale's way to Handpoint. [at] is `null` while the step is still to come. */
private data class TimelineStep(val label: String, val at: Instant?, val done: Boolean)

/**
 * An offline sale goes approved, sent, processed. An online sale is approved and settled by
 * Handpoint at once: there is no sending step.
 */
private fun Sale.timeline(): List<TimelineStep> = when (channel) {
    SaleChannel.ONLINE -> if (status == ProcessingStatus.UNKNOWN) {
        listOf(
            TimelineStep("Started online", approvedAt, done = true),
            TimelineStep("Not confirmed", null, done = false),
        )
    } else {
        listOf(
            TimelineStep("Approved online", approvedAt, done = true),
            TimelineStep(status.label(), statusCheckedAt, done = true),
        )
    }
    SaleChannel.OFFLINE -> listOf(
        TimelineStep("Approved offline", approvedAt, done = true),
        TimelineStep("Sent to Handpoint", sentAt, done = sentAt != null),
        if (status.isFinal) {
            TimelineStep(status.label(), statusCheckedAt, done = true)
        } else {
            TimelineStep("Processed by Handpoint", null, done = false)
        },
    )
}

@Composable
private fun Timeline(steps: List<TimelineStep>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            steps.forEachIndexed { index, step -> TimelineRow(step, last = index == steps.lastIndex) }
        }
    }
}

@Composable
private fun TimelineRow(step: TimelineStep, last: Boolean) {
    val accent = SafTheme.colors.accent
    val hairline = SafTheme.colors.hairline
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { if (!step.done) stateDescription = "Pending" },
    ) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(5.dp))
            val dot = Modifier.size(10.dp)
            Box(
                if (step.done) {
                    dot.background(accent, CircleShape)
                } else {
                    dot.border(2.dp, SafTheme.colors.textMuted, CircleShape)
                },
            )
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(hairline),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        val text = step.at?.let { "${step.label} · ${it.formatShort()}" } ?: step.label
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (step.done) MaterialTheme.colorScheme.onSurface else SafTheme.colors.textMuted,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun InfoCard(sale: Sale, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            InfoRow("Card", sale.maskedCard ?: "Card")
            HorizontalDivider(Modifier.padding(start = 16.dp), color = SafTheme.colors.hairline)
            InfoRow("Entry", sale.entryMode.label())
            HorizontalDivider(Modifier.padding(start = 16.dp), color = SafTheme.colors.hairline)
            InfoRow("Payment", if (sale.channel == SaleChannel.ONLINE) "Online" else "Offline")
            HorizontalDivider(Modifier.padding(start = 16.dp), color = SafTheme.colors.hairline)
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text("Reference", style = MaterialTheme.typography.bodyMedium, color = SafTheme.colors.textMuted)
                    Text(
                        text = sale.reference.value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Sale reference", sale.reference.value)))
                            snackbar.showSnackbar("Reference copied")
                        }
                    },
                ) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy reference")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SafTheme.colors.textMuted)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Merchant-facing name of how the card was read. */
fun EntryMode.label(): String = when (this) {
    EntryMode.CHIP -> "Chip"
    EntryMode.CONTACTLESS -> "Contactless"
    EntryMode.SWIPE -> "Swipe"
    EntryMode.MANUAL -> "Manual"
    EntryMode.UNKNOWN -> "Unknown"
}

/** Short date and time in the device's locale and time zone. */
private fun Instant.formatShort(): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(this)

@SafPreviews
@Composable
private fun SaleDetailScreenPreview() = SafTheme {
    val usd = Currency.getInstance("USD")
    SaleDetailScreen(
        state = SaleDetailUiState(
            sale = Sale(
                reference = SaleReference("7c1e2b1a-5d4f-4a2e-9b7d-3f0c8e6da91f"),
                amount = Money(820, usd),
                maskedCard = "Visa •• 0071",
                entryMode = EntryMode.CONTACTLESS,
                approvedAt = Instant.parse("2026-10-22T08:30:00Z"),
                sentAt = Instant.parse("2026-10-22T11:02:00Z"),
                status = ProcessingStatus.SUCCEEDED,
                statusCheckedAt = Instant.parse("2026-10-22T11:02:30Z"),
                merchantReceipt = null,
                customerReceipt = null,
            ),
            isLoading = false,
        ),
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        onRefresh = {},
        onDelete = {},
    )
}
