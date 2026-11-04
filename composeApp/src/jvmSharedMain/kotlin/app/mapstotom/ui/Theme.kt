package app.mapstotom.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The one theme every screen renders from. Nothing else in the UI hardcodes a colour,
 * a spacing value or a radius. Contrast of every text/background pair was measured
 * (WCAG 2.2 AA, 4.5:1 text, 3:1 control borders) in both themes before use.
 */

// ---------------------------------------------------------------- semantic colours

@Immutable
data class Tokens(
    val page: Color,          // window background (warm white / near black)
    val surface: Color,       // panels
    val sunken: Color,        // input wells, selected rows
    val text: Color,          // off-black / off-white body text
    val textMuted: Color,     // secondary text
    val hairline: Color,      // decorative 1 px dividers and panel edges
    val borderStrong: Color,  // essential control borders (3:1)
    val action: Color,        // the one accent: primary actions, links, selection
    val onAction: Color,
    val actionSoft: Color,    // selected nav item, soft emphasis
    val onActionSoft: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,        // destructive actions, errors
    val onDanger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
)

val LightTokens = Tokens(
    page = Color(0xFFF6F6F3), surface = Color(0xFFFCFCFA), sunken = Color(0xFFEEEEEA),
    text = Color(0xFF1C1D1F), textMuted = Color(0xFF585C63),
    hairline = Color(0xFFE1E1DC), borderStrong = Color(0xFF83888F),
    action = Color(0xFF1D5BBF), onAction = Color(0xFFFCFCFA),
    actionSoft = Color(0xFFE2EBF9), onActionSoft = Color(0xFF123A73),
    ok = Color(0xFF25734A), warn = Color(0xFF8A5A00),
    danger = Color(0xFFB3261E), onDanger = Color(0xFFFCFCFA),
    dangerSoft = Color(0xFFFBE4E2), onDangerSoft = Color(0xFF7A1612),
)

val DarkTokens = Tokens(
    page = Color(0xFF111214), surface = Color(0xFF18191B), sunken = Color(0xFF222326),
    text = Color(0xFFE7E7E4), textMuted = Color(0xFFA6AAB1),
    hairline = Color(0xFF2D2F33), borderStrong = Color(0xFF6E737B),
    action = Color(0xFF8DB5F7), onAction = Color(0xFF0C2A57),
    actionSoft = Color(0xFF1E3558), onActionSoft = Color(0xFFD6E4FB),
    ok = Color(0xFF7BD3A0), warn = Color(0xFFE6B54A),
    danger = Color(0xFFF2A7A1), onDanger = Color(0xFF4A0E0A),
    dangerSoft = Color(0xFF4A1A17), onDangerSoft = Color(0xFFFBD9D6),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

// ---------------------------------------------------------------- spacing (4 dp base)

object Space {
    val xxs = 2.dp; val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp
    val xl = 24.dp; val xxl = 32.dp; val xxxl = 48.dp
    /** Comfortable reading measure for prose (about 70 characters at body size). */
    val measure = 620.dp
    /** Widest the content column grows on big screens. */
    val column = 1040.dp
}

// ---------------------------------------------------------------- radius: one "soft" language

object Radius {
    val control = RoundedCornerShape(8.dp)
    val panel = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(50)
}

// ---------------------------------------------------------------- type (Major Third, 16 sp body)

private val Type = Typography(
    // Screen titles: 40 sp, 2.5x body, tight leading.
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun MapsToTomTheme(dark: Boolean, content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = t.action, onPrimary = t.onAction,
        primaryContainer = t.actionSoft, onPrimaryContainer = t.onActionSoft,
        secondary = t.action, onSecondary = t.onAction,
        secondaryContainer = t.actionSoft, onSecondaryContainer = t.onActionSoft,
        tertiary = t.warn, onTertiary = t.page,
        background = t.page, onBackground = t.text,
        surface = t.surface, onSurface = t.text,
        surfaceVariant = t.sunken, onSurfaceVariant = t.textMuted,
        surfaceContainerLowest = t.surface, surfaceContainerLow = t.surface, surfaceContainer = t.page,
        surfaceContainerHigh = t.sunken, surfaceContainerHighest = t.sunken,
        surfaceTint = Color.Transparent,
        outline = t.borderStrong, outlineVariant = t.hairline,
        error = t.danger, onError = t.onDanger, errorContainer = t.dangerSoft, onErrorContainer = t.onDangerSoft,
    )
    // No Surface at the root, so set the default icon/text colour here, or icons are black on the dark theme.
    androidx.compose.runtime.CompositionLocalProvider(LocalTokens provides t, androidx.compose.material3.LocalContentColor provides t.text) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Type,
            shapes = Shapes(small = Radius.control, medium = Radius.control, large = Radius.panel),
            content = content,
        )
    }
}
