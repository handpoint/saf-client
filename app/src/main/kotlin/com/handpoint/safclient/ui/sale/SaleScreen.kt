package com.handpoint.safclient.ui.sale

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.TerminalConnection

/** The charge flow screen, wired to [SaleViewModel]. [onDone] leaves the flow (back to Home). */
@Composable
fun SaleRoute(viewModel: SaleViewModel, onDone: () -> Unit) {
    val amount by viewModel.amount.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val printing by viewModel.printing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var shakeSignal by remember { mutableIntStateOf(0) }

    LaunchedEffect(viewModel) {
        viewModel.entryRejected.collect {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            shakeSignal++
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    SaleScreen(
        amount = amount,
        state = state,
        connection = connection,
        printing = printing,
        shakeSignal = shakeSignal,
        snackbar = snackbar,
        actions = SaleActions(
            onDigit = viewModel::onDigit,
            onDoubleZero = viewModel::onDoubleZero,
            onBackspace = viewModel::onBackspace,
            onCharge = viewModel::charge,
            onCancel = viewModel::cancel,
            onPrint = viewModel::printReceipt,
            onTryAgain = viewModel::tryAgain,
            onDone = onDone,
        ),
    )
}

/** What the charge flow screen can ask for. */
class SaleActions(
    val onDigit: (Int) -> Unit = {},
    val onDoubleZero: () -> Unit = {},
    val onBackspace: () -> Unit = {},
    val onCharge: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onPrint: () -> Unit = {},
    val onTryAgain: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/**
 * Amount, card and result in one screen, switched with [AnimatedContent].
 *
 * While the sale runs, system back never leaves the screen: it cancels when the terminal allows
 * it and does nothing otherwise, so a sale is never left running unseen.
 */
@Composable
fun SaleScreen(
    amount: Money,
    state: ChargeUiState,
    connection: TerminalConnection,
    printing: Boolean,
    shakeSignal: Int,
    snackbar: SnackbarHostState,
    actions: SaleActions,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state is ChargeUiState.InProgress) {
        val step = (state as? ChargeUiState.InProgress)?.step
        if (step?.cancelAllowed == true) actions.onCancel()
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                if (targetState is ChargeUiState.Result) {
                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f)) togetherWith fadeOut(tween(120))
                } else {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                }
            },
            // The Amount step has its own top bar: mark the insets as used, so it does not pad
            // for the status bar a second time.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            label = "chargeState",
        ) { current ->
            when (current) {
                ChargeUiState.Idle -> AmountContent(
                    amount = amount,
                    connection = connection,
                    shakeSignal = shakeSignal,
                    onDigit = actions.onDigit,
                    onDoubleZero = actions.onDoubleZero,
                    onBackspace = actions.onBackspace,
                    onCharge = actions.onCharge,
                    onBack = actions.onDone,
                )
                is ChargeUiState.InProgress -> CardStepContent(
                    amount = amount,
                    step = current.step,
                    onCancel = actions.onCancel,
                )
                is ChargeUiState.Result -> ResultContent(
                    outcome = current.outcome,
                    amount = amount,
                    printing = printing,
                    onPrint = actions.onPrint,
                    onTryAgain = actions.onTryAgain,
                    onDone = actions.onDone,
                )
            }
        }
    }
}
