package lk.coopfed.knoweb.till.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The back office's design tokens (web/src/design/tokens.css) as the till's colours, so the till
 * and the back office look like one product (research report 7A.2).
 */
object TillColors {
    val surface = Color(0xFFFFFFFF)
    val surfaceSubtle = Color(0xFFF8F9FB)
    val text = Color(0xFF1F2937)
    val textMuted = Color(0xFF64748B)
    val accent = Color(0xFF8E0E5B)
    val onAccent = Color(0xFFFFFFFF)
    val issuedText = Color(0xFF1B5E20)
    val issuedBg = Color(0xFFDDF6E5)
    val disputedText = Color(0xFF8A4B00)
    val disputedBg = Color(0xFFFFF2D9)
    val alertText = Color(0xFFB00020)
    val alertBg = Color(0xFFFDE2E4)
}

@Composable
fun TillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = TillColors.accent,
            onPrimary = TillColors.onAccent,
            surface = TillColors.surface,
            onSurface = TillColors.text,
            background = TillColors.surfaceSubtle,
            onBackground = TillColors.text,
            error = TillColors.alertText,
            errorContainer = TillColors.alertBg,
        ),
        content = content,
    )
}
