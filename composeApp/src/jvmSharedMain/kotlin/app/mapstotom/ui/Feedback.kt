package app.mapstotom.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mapstotom.Step
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Short clock time for "Saved 14:02". */
fun clock(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("HH:mm"))

/** Soft background for a note of this status: the accent's soft tint, the danger tint, or sunken. */
@Composable
private fun noteColors(s: Status) = LocalTokens.current.let {
    when (s) {
        Status.BAD -> it.dangerSoft to it.onDangerSoft
        Status.OK, Status.NONE -> it.actionSoft to it.onActionSoft
        Status.WARN -> it.sunken to it.text
    }
}

/**
 * A result or warning shown where it happened: icon, sentence, the time, an optional action and a close ×.
 * Flat: a soft tint and a hairline edge, no shadow.
 */
@Composable
fun StatusNote(
    status: Status,
    text: String,
    modifier: Modifier = Modifier,
    time: LocalTime? = null,
    icon: ImageVector = statusIcon(status),
    onClose: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalTokens.current
    val (bg, fg) = noteColors(status)
    Row(
        modifier.fillMaxWidth().widthIn(max = Space.measure + 80.dp)
            .background(bg, Radius.control).border(BorderStroke(1.dp, t.hairline), Radius.control)
            .padding(start = Space.md, top = Space.sm, bottom = Space.sm, end = if (onClose != null) Space.xxs else Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = if (status == Status.WARN) t.warn else if (status == Status.BAD) t.danger else fg,
            modifier = Modifier.padding(top = Space.xxs).size(18.dp))
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f).padding(top = Space.xxs)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
            if (time != null) Text(clock(time), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, "Dismiss", tint = t.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

/** The steps of a long job: ✓ done, spinner running, ○ to come, and the user's own step after it. */
@Composable
fun StepList(steps: List<Step>, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Panel(modifier.widthIn(max = Space.measure + 80.dp), padding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
        steps.forEachIndexed { i, s ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when (s.state) {
                        Step.State.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.action)
                        Step.State.DONE -> Icon(statusIcon(Status.OK), null, tint = t.ok, modifier = Modifier.size(18.dp))
                        Step.State.FAILED -> Icon(statusIcon(Status.BAD), null, tint = t.danger, modifier = Modifier.size(18.dp))
                        Step.State.TODO -> Icon(statusIcon(Status.NONE), null, tint = t.textMuted, modifier = Modifier.size(18.dp))
                        Step.State.AFTER -> Icon(Icons.Filled.PowerSettingsNew, null, tint = t.warn, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(s.label + if (s.state == Step.State.AFTER) " (you, afterwards)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (s.state == Step.State.TODO) t.textMuted else t.text)
                    s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textMuted) }
                }
            }
        }
    }
}

/** Grey placeholder rows while the TomTom is read; a slow pulse, no shimmer. */
@Composable
fun SkeletonRows(rows: Int = 3, modifier: Modifier = Modifier, lineHeight: Dp = 14.dp) {
    val t = LocalTokens.current
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Column(modifier.fillMaxWidth().alpha(pulse)) {
        repeat(rows) { i ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).background(t.sunken, Radius.pill))
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.55f else 0.4f).height(lineHeight).background(t.sunken, Radius.control))
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.8f else 0.7f).height(lineHeight - 4.dp).background(t.sunken, Radius.control))
                }
            }
        }
    }
}

/** Nothing here yet: an icon, one line, and the action that fills it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    Column(modifier.fillMaxWidth().padding(vertical = Space.xl, horizontal = Space.md), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(t.sunken, Radius.pill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = t.textMuted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(Space.md))
        Text(title, style = MaterialTheme.typography.titleSmall, color = t.text)
        if (body != null) {
            Spacer(Modifier.height(Space.xs))
            Text(body, Modifier.widthIn(max = 420.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.height(Space.md)); action() }
    }
}
