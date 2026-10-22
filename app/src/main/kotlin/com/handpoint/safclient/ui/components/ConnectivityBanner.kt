package com.handpoint.safclient.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.ModeState
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.ui.theme.SafPreviewSurface
import com.handpoint.safclient.ui.theme.SafPreviews
import com.handpoint.safclient.ui.theme.SafTheme
import com.handpoint.safclient.ui.theme.Tone

/** Test tag of the progress bar a switching banner shows. */
internal const val BANNER_PROGRESS_TAG = "connectivity-banner-progress"

/** Test tag of the banner itself. */
const val CONNECTIVITY_BANNER_TAG = "connectivity-banner"

/** What the connectivity banner tells the merchant. */
enum class BannerKind {
    /** Online sales: each sale is approved at once. */
    ONLINE,

    /** No network: sales are stored on the terminal. */
    NO_CONNECTION,

    /** The network is back, and sales stored offline wait to be sent. */
    WAITING_TO_SEND,

    /** The network is back, but the online API could not start: still storing sales. */
    ONLINE_UNAVAILABLE,

    SWITCHING_TO_ONLINE,
    SWITCHING_TO_OFFLINE,
}

/**
 * Which banner to show for [state], with [waitingToSend] sales stored on the terminal.
 *
 * With the network back and nothing waiting, the app is about to switch to online sales (it
 * waits until no sale, send or status check runs), so the banner already says it is getting
 * ready.
 */
fun bannerKind(state: ModeState, waitingToSend: Int): BannerKind = when {
    state.switchingTo == PaymentMode.OFFLINE -> BannerKind.SWITCHING_TO_OFFLINE
    state.switchingTo == PaymentMode.ONLINE -> BannerKind.SWITCHING_TO_ONLINE
    state.mode == PaymentMode.ONLINE -> BannerKind.ONLINE
    state.network == NetworkStatus.OFFLINE -> BannerKind.NO_CONNECTION
    waitingToSend > 0 -> BannerKind.WAITING_TO_SEND
    state.onlineUnavailable -> BannerKind.ONLINE_UNAVAILABLE
    else -> BannerKind.SWITCHING_TO_ONLINE
}

/**
 * The persistent banner that says how sales are taken right now: online, stored offline, or
 * switching between the two. Shown at the top of Home and of the sale flow.
 *
 * Online it is quiet (a green dot and "Online"); offline it is amber and explains that sales
 * are stored and sent later. When the network is back and sales wait to be sent, it offers
 * [onSendNow] (left out when `null`, for example during a sale). Changes are announced to
 * screen readers and cross-fade, unless system animations are off.
 */
@Composable
fun ConnectivityBanner(
    state: ModeState,
    waitingToSend: Int,
    modifier: Modifier = Modifier,
    onSendNow: (() -> Unit)? = null,
    sendEnabled: Boolean = true,
) {
    val kind = bannerKind(state, waitingToSend)
    val animate = animationsEnabled()
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            if (animate) {
                (fadeIn(tween(220)) togetherWith fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            } else {
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(CONNECTIVITY_BANNER_TAG),
        label = "connectivityBanner",
    ) { current ->
        when (current) {
            BannerKind.ONLINE -> OnlineBanner()
            BannerKind.NO_CONNECTION -> StoringBanner(title = "No connection")
            BannerKind.ONLINE_UNAVAILABLE -> StoringBanner(title = "Online sales unavailable")
            BannerKind.SWITCHING_TO_ONLINE -> SwitchingBanner("Back online · getting ready…", animate)
            BannerKind.SWITCHING_TO_OFFLINE -> SwitchingBanner("Switching to offline sales…", animate)
            BannerKind.WAITING_TO_SEND -> WaitingBanner(waitingToSend, onSendNow, sendEnabled)
        }
    }
}

/** Quiet: online is the normal case and needs no attention. */
@Composable
private fun OnlineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .announced()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(SafTheme.colors.paid.content)
        Spacer(Modifier.width(8.dp))
        Text("Online", style = MaterialTheme.typography.labelLarge, color = SafTheme.colors.textMuted)
    }
}

/** Amber and persistent: sales are stored on the terminal, not charged yet. */
@Composable
private fun StoringBanner(title: String) {
    BannerSurface(SafTheme.colors.stored) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(LocalContentColor.current)
            Spacer(Modifier.width(10.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                    append(" · Sales are stored on this terminal and sent later")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SwitchingBanner(text: String, animate: Boolean) {
    BannerSurface(SafTheme.colors.processing) {
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            // An indeterminate bar moves forever: with animations off, the "…" says it all.
            if (animate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BANNER_PROGRESS_TAG),
                    color = LocalContentColor.current,
                    trackColor = Color.Transparent,
                )
            } else {
                Spacer(Modifier.testTag(BANNER_PROGRESS_TAG))
            }
        }
    }
}

@Composable
private fun WaitingBanner(waiting: Int, onSendNow: (() -> Unit)?, sendEnabled: Boolean) {
    BannerSurface(SafTheme.colors.processing) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Back online · " + if (waiting == 1) "1 sale waiting to send" else "$waiting sales waiting to send",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            if (onSendNow != null) {
                TextButton(
                    onClick = onSendNow,
                    enabled = sendEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = LocalContentColor.current,
                        disabledContentColor = LocalContentColor.current.copy(alpha = 0.5f),
                    ),
                ) {
                    Text("Send now", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A tinted, rounded surface in [tone]; its text uses the tone's content colour (WCAG AA on the container). */
@Composable
private fun BannerSurface(tone: Tone, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .announced(),
        shape = MaterialTheme.shapes.medium,
        color = tone.container,
        contentColor = tone.content,
        content = content,
    )
}

/** Read out as one message, and again when it changes. */
private fun Modifier.announced(): Modifier = semantics(mergeDescendants = true) {
    liveRegion = LiveRegionMode.Polite
}

@Composable
private fun Dot(color: Color) {
    Spacer(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

@SafPreviews
@Composable
private fun ConnectivityBannerPreview() = SafPreviewSurface {
    val back = ModeState(mode = PaymentMode.OFFLINE, network = NetworkStatus.ONLINE)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectivityBanner(ModeState(mode = PaymentMode.ONLINE, network = NetworkStatus.ONLINE), 0)
        ConnectivityBanner(ModeState(network = NetworkStatus.OFFLINE), 2)
        ConnectivityBanner(back.copy(switchingTo = PaymentMode.ONLINE), 0)
        ConnectivityBanner(back, 3, onSendNow = {})
        ConnectivityBanner(back.copy(onlineUnavailable = true), 0)
    }
}
