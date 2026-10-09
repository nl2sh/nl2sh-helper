package ernest.nl2sh.helper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CardSection(title: String, top: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = top.dp).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text(title, color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
internal fun ActionButton(text: String, enabled: Boolean, modifier: Modifier = Modifier,
                                     destructive: Boolean = false, onClick: () -> Unit) {
    val active = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            contentColor = active,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        border = ButtonDefaults.outlinedButtonBorder(enabled).copy(brush = SolidColor(if (enabled) active else MaterialTheme.colorScheme.outline))) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
