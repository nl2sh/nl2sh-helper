package ernest.nl2sh.helper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource

@Composable
fun Nl2shTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = colorResource(R.color.ui_accent),
        secondary = colorResource(R.color.ui_cyan),
        tertiary = colorResource(R.color.ui_special),
        background = colorResource(R.color.ui_background),
        surface = colorResource(R.color.ui_background_alt),
        surfaceVariant = colorResource(R.color.ui_background_alt),
        outline = colorResource(R.color.ui_border),
        onPrimary = colorResource(R.color.ui_background),
        onBackground = colorResource(R.color.ui_text_primary),
        onSurface = colorResource(R.color.ui_text_primary),
        onSurfaceVariant = colorResource(R.color.ui_text_secondary),
        error = colorResource(R.color.ui_error),
        onError = colorResource(R.color.ui_background),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
