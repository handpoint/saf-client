package com.handpoint.safclient.ui.home

import androidx.compose.foundation.background
import com.handpoint.safclient.ui.theme.PillShape
import com.handpoint.safclient.ui.components.ButtonEmphasis
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handpoint.safclient.R
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.ui.components.AmountText
import com.handpoint.safclient.ui.components.ConnectionChip
import com.handpoint.safclient.ui.components.EmptyState
import com.handpoint.safclient.ui.components.PrimaryButton
import com.handpoint.safclient.ui.components.SaleRow
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Currency

/** Which sales the list below the hero card shows. */
internal enum class HomeFilter { TODAY, STORED }

/** Test tag of the list header (Today / Waiting to send filters). */
internal const val LIST_HEADER_TAG = "home-list-header"

/** Index of the list header in the Home list: after the hero card and the New sale block. */
private const val LIST_HEADER_INDEX = 2

/** Home wired to its [HomeViewModel]; reconciles again each time the screen comes back. */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onNewSale: () -> Unit,
    onSendNow: () -> Unit,
    onOpenSale: (SaleReference) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    HomeScreen(
        state = state,
        onNewSale = onNewSale,
        onSendNow = onSendNow,
        onOpenSale = onOpenSale,
        onOpenSettings = onOpenSettings,
        onRetryConnection = viewModel::retryConnection,
    )
}

/**
 * The merchant's dashboard: the amount waiting to be sent (with Send now and View), the
 * New sale action, and today's sales with their status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onNewSale: () -> Unit,
    onSendNow: () -> Unit,
    onOpenSale: (SaleReference) -> Unit,
    onOpenSettings: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(HomeFilter.TODAY) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val background = MaterialTheme.colorScheme.background

    Scaffold(
        modifier = modifier,
        containerColor = background,
        topBar = {
            TopAppBar(
                title = {
                    Text(state.merchantName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    ConnectionStatus(state.connection, onRetryConnection)
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = background,
                ),
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val sales = if (filter == HomeFilter.TODAY) state.today else state.stored
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "pending") {
                PendingCard(
                    state = state,
                    onSendNow = onSendNow,
                    onView = {
                        filter = HomeFilter.STORED
                        scope.launch { listState.animateScrollToItem(LIST_HEADER_INDEX) }
                    },
                )
            }
            item(key = "new-sale") { NewSale(state.canTakeSale, onNewSale) }
            item(key = "header") {
                ListHeader(filter = filter, storedCount = state.pendingCount, onFilter = { filter = it })
            }
            when {
                state.isLoading -> Unit
                sales.isEmpty() -> item(key = "empty") { EmptyList(filter) }
                else -> itemsIndexed(sales, key = { _, sale -> sale.reference.value }) { index, sale ->
                    GroupedRow(index = index, count = sales.size) {
                        SaleRow(sale = sale, onClick = { onOpenSale(sale.reference) })
                    }
                }
            }
        }
    }
}

/** The dark hero card: what is still stored on the terminal, with View and Send now. */
@Composable
private fun PendingCard(state: HomeUiState, onSendNow: () -> Unit, onView: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "WAITING TO SEND",
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.72f),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            when {
                state.isLoading -> Spacer(Modifier.height(HeroContentHeight))
                state.pendingCount == 0 -> AllSent(state.isSending)
                else -> Waiting(state, onSendNow, onView)
            }
        }
    }
}

/** Height of the hero card's content while the journal loads, so the card does not jump. */
private val HeroContentHeight = 112.dp

@Composable
private fun Waiting(state: HomeUiState, onSendNow: () -> Unit, onView: () -> Unit) {
    state.pending?.let { AmountText(it, SafTheme.amounts.hero, Modifier.testTag("pending-total")) }
    Text(
        text = storedOnTerminal(state.pendingCount),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current.copy(alpha = 0.8f),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardButton(text = "View", onClick = onView, filled = false, modifier = Modifier.weight(1f))
        CardButton(
            text = if (state.isSending) "Sending…" else "Send now",
            onClick = onSendNow,
            filled = true,
            enabled = state.canSend,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AllSent(isSending: Boolean) {
    Text("All sales sent", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (isSending) "Finishing the last run…" else "Nothing is waiting on this terminal.",
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current.copy(alpha = 0.8f),
    )
}

private fun storedOnTerminal(count: Int): String =
    if (count == 1) "1 sale stored on this terminal" else "$count sales stored on this terminal"

/** A button on the hero card: filled (light on the dark card) or quiet (translucent). */
@Composable
private fun CardButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val onCard = LocalContentColor.current
    val card = MaterialTheme.colorScheme.inverseSurface
    val container = if (filled) onCard else onCard.copy(alpha = 0.14f)
    val content = if (filled) card else onCard
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = onCard.copy(alpha = 0.14f),
            disabledContentColor = onCard.copy(alpha = 0.5f),
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** New sale, which needs a connected terminal; says why when it is not available. */
@Composable
private fun NewSale(enabled: Boolean, onNewSale: () -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        PrimaryButton(text = "New sale", onClick = onNewSale, enabled = enabled, emphasis = ButtonEmphasis.Accent)
        if (!enabled) {
            Text(
                text = "Connect the terminal to take sales",
                style = MaterialTheme.typography.bodyMedium,
                color = SafTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

/**
 * The connection chip. When the terminal is not connected, a tap tries to connect again;
 * screen readers announce it as a button.
 */
@Composable
private fun ConnectionStatus(connection: TerminalConnection, onRetry: () -> Unit) {
    val canRetry = connection == TerminalConnection.DISCONNECTED || connection == TerminalConnection.NOT_CONFIGURED
    val modifier = if (canRetry) {
        Modifier
            .clip(PillShape)
            .clickable(role = Role.Button, onClickLabel = "Connect the terminal", onClick = onRetry)
    } else {
        Modifier
    }
    ConnectionChip(connection, modifier)
}

@Composable
private fun ListHeader(filter: HomeFilter, storedCount: Int, onFilter: (HomeFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LIST_HEADER_TAG)
            .padding(top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter == HomeFilter.TODAY,
            onClick = { onFilter(HomeFilter.TODAY) },
            label = { Text("Today") },
        )
        FilterChip(
            selected = filter == HomeFilter.STORED,
            onClick = { onFilter(HomeFilter.STORED) },
            label = { Text(if (storedCount > 0) "Waiting to send · $storedCount" else "Waiting to send") },
        )
    }
}

@Composable
private fun EmptyList(filter: HomeFilter) = when (filter) {
    HomeFilter.TODAY -> EmptyState(
        title = "No sales today",
        body = "Sales you take appear here with their status.",
    )
    HomeFilter.STORED -> EmptyState(
        title = "Nothing waiting to send",
        body = "Every stored sale has been sent to Handpoint.",
    )
}

/** One row of the list card: rounded at the ends of the group, a hairline between rows. */
@Composable
private fun GroupedRow(index: Int, count: Int, content: @Composable () -> Unit) {
    val radius = 14.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) radius else 0.dp,
        topEnd = if (index == 0) radius else 0.dp,
        bottomStart = if (index == count - 1) radius else 0.dp,
        bottomEnd = if (index == count - 1) radius else 0.dp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        content()
        if (index < count - 1) {
            HorizontalDivider(Modifier.padding(start = 16.dp), color = SafTheme.colors.hairline)
        }
    }
}

@SafPreviews
@Composable
private fun HomeScreenPreview() = SafTheme {
    HomeScreen(previewState(), onNewSale = {}, onSendNow = {}, onOpenSale = {}, onOpenSettings = {}, onRetryConnection = {})
}

@SafPreviews
@Composable
private fun HomeScreenOfflinePreview() = SafTheme {
    HomeScreen(
        previewState().copy(connection = TerminalConnection.DISCONNECTED, pending = null, pendingCount = 0, stored = emptyList()),
        onNewSale = {},
        onSendNow = {},
        onOpenSale = {},
        onOpenSettings = {},
        onRetryConnection = {},
    )
}

private fun previewState(): HomeUiState {
    val usd = Currency.getInstance("USD")
    fun sale(ref: String, cents: Long, card: String, status: ProcessingStatus, at: String) = Sale(
        reference = SaleReference(ref),
        amount = Money(cents, usd),
        maskedCard = card,
        entryMode = EntryMode.CONTACTLESS,
        approvedAt = Instant.parse(at),
        sentAt = null,
        status = status,
        statusCheckedAt = null,
        merchantReceipt = null,
        customerReceipt = null,
    )
    val today = listOf(
        sale("a", 1250, "•••• 4242", ProcessingStatus.STORED, "2026-10-22T10:14:00Z"),
        sale("b", 4700, "•••• 1188", ProcessingStatus.STORED, "2026-10-22T09:52:00Z"),
        sale("c", 820, "•••• 0071", ProcessingStatus.SUCCEEDED, "2026-10-22T08:30:00Z"),
    )
    val stored = today.filter { it.status == ProcessingStatus.STORED }
    return HomeUiState(
        merchantName = "Café Aurora",
        connection = TerminalConnection.CONNECTED,
        pending = Money(5950, usd),
        pendingCount = stored.size,
        today = today,
        stored = stored,
        isLoading = false,
    )
}
