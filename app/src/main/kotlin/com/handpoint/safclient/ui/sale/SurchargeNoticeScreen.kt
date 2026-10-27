package com.handpoint.safclient.ui.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleSurcharge
import com.handpoint.safclient.ui.components.SurchargeBreakdown
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import java.math.BigDecimal
import java.util.Currency

/** What the customer reads before paying; the same words the card networks' surcharge rules ask for. */
const val SURCHARGE_NOTICE_TEXT =
    "The total includes the surcharge. If your card is debit or prepaid, the surcharge is removed."

/**
 * The surcharge notice: the percent charged and what it comes to in money, shown to the
 * customer before the card is read. The sale does not start until they accept.
 */
@Composable
fun SurchargeNoticeContent(
    base: Money,
    surcharge: SaleSurcharge,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Surcharge notice",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = SafTheme.colors.processing.container,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Text(
                text = SURCHARGE_NOTICE_TEXT,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        SurchargeBreakdown(base, surcharge)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeButton(
                text = "Accept and continue",
                onClick = onAccept,
                container = SafTheme.colors.paid.content,
            )
            NoticeButton(
                text = "Cancel",
                onClick = onCancel,
                container = SafTheme.colors.failed.content,
            )
        }
    }
}

@Composable
private fun NoticeButton(text: String, onClick: () -> Unit, container: androidx.compose.ui.graphics.Color) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.background,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@SafPreviews
@Composable
private fun SurchargeNoticePreview() = SafPreviewSurface {
    val base = Money(5000, Currency.getInstance("USD"))
    SurchargeNoticeContent(base, SaleSurcharge.of(base, BigDecimal("3")), onAccept = {}, onCancel = {})
}
