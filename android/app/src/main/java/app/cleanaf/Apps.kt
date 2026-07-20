package app.cleanaf

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class RefEntry(val match: String, val desc: String, val sub: Boolean, val alt: String)

data class InstalledApp(
    val name: String,
    val pkg: String,
    val size: Long,
    val system: Boolean,
    val ref: RefEntry?,
)

private fun loadRef(context: Context): List<RefEntry> {
    return try {
        val text = context.assets.open("apps.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RefEntry(o.getString("match"), o.getString("desc"), o.optBoolean("sub", false), o.optString("alt", ""))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private suspend fun loadApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val ref = loadRef(context)
    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
    val useSizes = hasUsageAccess(context)
    val apps = pm.getInstalledApplications(0)
    apps.map { ai ->
        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            ai.packageName
        }
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val size = if (useSizes) {
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                s.appBytes + s.dataBytes
            } catch (e: Exception) {
                -1L
            }
        } else {
            -1L
        }
        val match = ref.firstOrNull {
            label.lowercase().contains(it.match) || ai.packageName.lowercase().contains(it.match)
        }
        InstalledApp(label, ai.packageName, size, system, match)
    }.sortedByDescending { it.size }
}

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showSystem by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        usage = hasUsageAccess(context)
        apps = loadApps(context)
        loading = false
    }

    val visible = apps.filter { showSystem || !it.system }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Apps & subscriptions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "${visible.size} apps" + if (usage) " · sorted by size" else " · turn on usage access to see sizes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (!usage) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "To show each app's size, Clean AF needs Usage Access (a one-time system toggle).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }) { Text("Open Usage Access settings") }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Show system apps") })
            Spacer(Modifier.height(0.dp))
            OutlinedButton(onClick = { reloadKey++ }, modifier = Modifier.padding(start = 8.dp)) { Text("Refresh") }
        }
        Spacer(Modifier.height(10.dp))

        if (loading) {
            Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(visible) { app ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (app.size >= 0) fmtSize(app.size) else "—",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        app.ref?.let { r ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                (if (r.sub) "Paid subscription — " else "Free — ") + r.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.sub) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (r.alt.isNotBlank()) {
                                Text(
                                    "Free alternative: ${r.alt}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")),
                            )
                        }) { Text("Uninstall") }
                    }
                }
            }
        }
    }
}
