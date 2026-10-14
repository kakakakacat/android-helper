package kakacat.android.helper.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Compose 页面：布局检查里应该能看到 Text / Button / Checkbox 这些语义节点。 */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var count by remember { mutableIntStateOf(0) }
                var checked by remember { mutableStateOf(true) }
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Compose screen", style = MaterialTheme.typography.headlineSmall)
                    Text("clicked $count times", Modifier.testTag("counter"))
                    Button(onClick = { count++ }, modifier = Modifier.testTag("increment")) {
                        Text("Increment")
                    }
                    Row {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text("Checked", Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}
