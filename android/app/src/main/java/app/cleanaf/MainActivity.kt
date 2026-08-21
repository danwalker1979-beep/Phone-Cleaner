package app.cleanaf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CleanAfTheme { AppRoot() } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    ANALYZE("Media", Icons.Filled.Search),
    APPS("Apps", Icons.Filled.Apps),
    DOCS("Docs", Icons.Filled.Description),
    JUNK("Junk", Icons.Filled.CleaningServices),
    ASSISTANT("Chat", Icons.AutoMirrored.Filled.Chat),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.ANALYZE) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Clean AF") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.ANALYZE -> AnalyzeScreen()
                Tab.APPS -> AppsScreen()
                Tab.DOCS -> DocumentsScreen()
                Tab.JUNK -> LeftoversScreen()
                Tab.ASSISTANT -> AssistantScreen()
            }
        }
    }
}
