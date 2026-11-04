package app.mapstotom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A checkbox whose whole row, label included, is the click target. */
@Composable
fun CheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.clip(Radius.control).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(Space.md))
        content()
    }
}

/** A checkbox row with a plain text label. */
@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) =
    CheckRow(checked, onChange, modifier, enabled) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) LocalTokens.current.text else LocalTokens.current.textMuted)
    }

/**
 * Two or three choices side by side, where it must be clear which one is on: a pill with a hairline
 * divider, the chosen part tinted. Each part is as wide as its own label.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = LocalTokens.current
    val edge = if (enabled) t.borderStrong else t.hairline
    Row(modifier.height(36.dp).clip(Radius.pill).border(BorderStroke(1.dp, edge), Radius.pill).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(edge))
            Box(
                Modifier.fillMaxHeight().background(if (on) t.actionSoft else t.surface)
                    .selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = Space.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                    color = when { on -> t.onActionSoft; enabled -> t.textMuted; else -> t.textMuted.copy(alpha = 0.6f) })
            }
        }
    }
}

/** Accent swatches: a dot in the accent's colour for the current theme, its name, and a ring on the chosen one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentPicker(selected: Accent, onSelect: (Accent) -> Unit) {
    val t = LocalTokens.current
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Accent.entries.forEach { a ->
            val on = a == selected
            val c = if (t.dark) a.dark else a.light
            Row(
                Modifier.height(40.dp).clip(Radius.pill)
                    .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) t.action else t.borderStrong), Radius.pill)
                    .background(if (on) t.actionSoft else t.surface)
                    .selectable(on, role = Role.RadioButton) { onSelect(a) }
                    .padding(start = Space.sm, end = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(Radius.pill).background(c.action), contentAlignment = Alignment.Center) {
                    if (on) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = c.onAction)
                }
                Spacer(Modifier.width(Space.sm))
                Text(a.label, style = MaterialTheme.typography.labelLarge, color = if (on) t.onActionSoft else t.text)
            }
        }
    }
}

/** A filter box with a search icon and a × that clears it. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    OutlinedTextField(
        value, onChange, modifier, singleLine = true, shape = Radius.control,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (value.isEmpty()) null else ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear") } }),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = t.borderStrong, focusedBorderColor = t.action,
            unfocusedContainerColor = t.surface, focusedContainerColor = t.surface),
    )
}

/** A short hint when the pointer rests on [content] (or a long press on Android). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip(containerColor = t.text, contentColor = t.surface) { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** An icon button with its name as a hint: the icon alone doesn't say what it does. */
@Composable
fun TipIconButton(tip: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) =
    Tip(tip) { IconButton(onClick = onClick, enabled = enabled, content = content) }
