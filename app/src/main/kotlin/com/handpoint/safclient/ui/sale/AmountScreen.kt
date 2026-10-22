package com.handpoint.safclient.ui.sale

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.R
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.ui.components.AmountText
import com.handpoint.safclient.ui.components.ButtonEmphasis
import com.handpoint.safclient.ui.components.ConnectionChip
import com.handpoint.safclient.ui.components.Keypad
import com.handpoint.safclient.ui.components.PrimaryButton
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import java.util.Currency

/**
 * Amount entry: the amount large at the top, the keypad, and Charge.
 * [shakeSignal] goes up each time a key press is refused; the amount then gives a short shake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountContent(
    amount: Money,
    connection: TerminalConnection,
    shakeSignal: Int,
    onDigit: (Int) -> Unit,
    onDoubleZero: () -> Unit,
    onBackspace: () -> Unit,
    onCharge: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("New sale") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                }
            },
            actions = { ConnectionChip(connection, Modifier.padding(end = 12.dp)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ShakingAmount(amount, shakeSignal)
            }
            Keypad(onDigit = onDigit, onDoubleZero = onDoubleZero, onBackspace = onBackspace)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Charge ${amount.format()}",
                onClick = onCharge,
                enabled = amount.minorUnits > 0,
                emphasis = ButtonEmphasis.Accent,
            )
        }
    }
}

@Composable
private fun ShakingAmount(amount: Money, shakeSignal: Int) {
    val offset = remember { Animatable(0f) }
    val shakePx = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(shakeSignal) {
        if (shakeSignal == 0) return@LaunchedEffect
        offset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 320
                shakePx at 40
                -shakePx at 120
                shakePx * 0.6f at 200
                -shakePx * 0.3f at 260
            },
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.graphicsLayer { translationX = offset.value },
    ) {
        AmountText(
            money = amount,
            style = SafTheme.amounts.hero,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = amount.currency.currencyCode,
            style = MaterialTheme.typography.labelLarge,
            color = SafTheme.colors.textMuted,
        )
    }
}

@SafPreviews
@Composable
private fun AmountContentPreview() = SafPreviewSurface {
    AmountContent(
        amount = Money(1250, Currency.getInstance("USD")),
        connection = TerminalConnection.CONNECTED,
        shakeSignal = 0,
        onDigit = {},
        onDoubleZero = {},
        onBackspace = {},
        onCharge = {},
        onBack = {},
    )
}
