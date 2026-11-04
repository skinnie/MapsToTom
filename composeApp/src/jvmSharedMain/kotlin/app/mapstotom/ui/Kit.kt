package app.mapstotom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Status always shows as an icon plus words, never colour alone. */
enum class Status { OK, WARN, BAD, NONE }

@Composable
fun statusColor(s: Status) = LocalTokens.current.let {
    when (s) { Status.OK -> it.ok; Status.WARN -> it.warn; Status.BAD -> it.danger; Status.NONE -> it.textMuted }
}

fun statusIcon(s: Status): ImageVector = when (s) {
    Status.OK -> Icons.Filled.CheckCircle
    Status.WARN -> Icons.Filled.WarningAmber
    Status.BAD -> Icons.Filled.ErrorOutline
    Status.NONE -> Icons.Filled.RadioButtonUnchecked
}

/** Screen title (display type) with one plain sentence under it. The title is what the eye lands on. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = Space.xl)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = LocalTokens.current.text)
        if (subtitle != null) {
            Spacer(Modifier.height(Space.sm))
            Prose(subtitle, muted = true, big = true)
        }
    }
}

/** Paragraph text held to a readable line length. */
@Composable
fun Prose(text: String, muted: Boolean = true, big: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.widthIn(max = Space.measure),
        style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = if (muted) LocalTokens.current.textMuted else LocalTokens.current.text,
    )
}

/** Small uppercase group label above a block. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, color = LocalTokens.current.textMuted)
}

/** A flat panel: surface colour and a hairline edge, no shadow. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(Space.lg), content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .background(t.surface, Radius.panel)
            .border(BorderStroke(1.dp, t.hairline), Radius.panel)
            .padding(padding),
        content = content,
    )
}

/** Thin divider between rows of a list. */
@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = LocalTokens.current.hairline)

/** One line of a status list: icon, title, detail, and an optional action on the right. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun StatusRow(
    status: Status,
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // In a narrow column (a phone, or half of an upright tablet) the buttons go under the text,
    // so the text isn't squeezed to one word per line.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stack = trailing != null && maxWidth < STACK_BELOW
        Row(Modifier.fillMaxWidth().padding(vertical = Space.md), verticalAlignment = if (stack) Alignment.Top else Alignment.CenterVertically) {
            Icon(statusIcon(status), contentDescription = null, tint = statusColor(status), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = LocalTokens.current.text)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textMuted,
                    modifier = Modifier.widthIn(max = Space.measure))
                if (stack) {
                    Spacer(Modifier.height(Space.sm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically, content = trailing!!)
                    }
                }
            }
            if (trailing != null && !stack) {
                Spacer(Modifier.width(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}

/** Below this width a status row puts its buttons under the text. */
private val STACK_BELOW = 460.dp

/** "Label    value" definition row. */
@Composable
fun Fact(label: String, value: String, status: Status? = null) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(140.dp), style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
        if (status != null) {
            Icon(statusIcon(status), null, tint = statusColor(status), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.xs))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = t.text)
    }
}

// ---------------------------------------------------------------- buttons by intent

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) =
    Button(onClick, enabled = enabled, shape = Radius.control, contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm + Space.xxs)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

/** Neutral secondary action: outline, dark text, never a coloured fill. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    val t = LocalTokens.current
    OutlinedButton(onClick, enabled = enabled, shape = Radius.control,
        border = BorderStroke(1.dp, if (enabled) t.borderStrong else t.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.text),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm + Space.xxs)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Destructive action: the same danger colour on the trigger and on its confirm button. */
@Composable
fun DangerTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true, inline: Boolean = false) =
    TextButton(onClick, enabled = enabled, shape = Radius.control,
        contentPadding = if (inline) PaddingValues(horizontal = 0.dp, vertical = Space.sm) else ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = LocalTokens.current.danger)) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
fun DangerButton(text: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Button(onClick, shape = Radius.control, colors = ButtonDefaults.buttonColors(containerColor = t.danger, contentColor = t.onDanger)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Confirmation dialog for a destructive action. */
@Composable
fun ConfirmDanger(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalTokens.current.surface,
        shape = Radius.panel,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Prose(body, muted = false) },
        confirmButton = { DangerButton(confirm, onConfirm) },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
