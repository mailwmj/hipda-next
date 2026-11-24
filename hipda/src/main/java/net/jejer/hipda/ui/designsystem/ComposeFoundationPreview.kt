package net.jejer.hipda.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Composable
internal fun ComposeFoundationPlaceholder(label: String = "HiPDA Next") {
    MaterialTheme {
        Surface {
            Text(text = label)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposeFoundationPlaceholderPreview() {
    ComposeFoundationPlaceholder()
}
