package ernest.nl2sh.helper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.nl2sh.helper.R
import ernest.nl2sh.helper.ui.HelperUiState
import ernest.nl2sh.helper.ui.StatusTone

@Composable
internal fun HelperUiState.StatusCard() {
    val toneColor = when (status.tone) {
        StatusTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.WORKING -> MaterialTheme.colorScheme.primary
        StatusTone.SUCCESS -> colorResource(R.color.ui_success)
        StatusTone.WARNING -> colorResource(R.color.ui_warning)
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, toneColor, RoundedCornerShape(8.dp)).padding(16.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status.tone.label, color = toneColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (status.tone == StatusTone.WORKING) CircularProgressIndicator(Modifier.size(20.dp), color = toneColor, strokeWidth = 2.dp)
        }
        Text(status.message, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
