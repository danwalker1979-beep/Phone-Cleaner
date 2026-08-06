package app.cleanaf

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class RefEntry(val match: String, val desc: String, val sub: Boolean, val alt: String)

data class InstalledApp(
    val name: String,
    val pkg: String,
    val size: Long,
    val system: Boolean,
    val lastUsed: Long,
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

fun lastUsedLabel(lastUsed: Long): String {
    if (lastUsed <= 0) return "Not opened in the last year"
    val days = (System.currentTimeMillis() - lastUsed) / 86_400_000L
    return when {
        days <= 0 -> "Opened today"
        days == 1L -> "Opened yesterday"
        days < 30 -> "Opened $days days ago"
        days < 60 -> "Opened about a month ago"
        days < 365 -> "Opened about ${days / 30} months ago"
        else -> "Opened over a year ago"
    }
}

private fun unusedDays(lastUsed: Long): Long =
    if (lastUsed <= 0) Long.MAX_VALUE else (System.currentTimeMillis() - lastUsed) / 86_400_000L

private suspend fun loadApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val ref = loadRef(context)
    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val useStats = hasUsageAccess(context)
    val now = System.currentTimeMillis()
    val lastUsedMap: Map<String, Long> = if (useStats) {
        try {
            usm.queryAndAggregateUsageStats(now - 365L * 86_400_000L, now)
                .mapValues { it.value.lastTimeUsed }
        } catch (e: Exception) {
            emptyMap()
        }
    } else {
        emptyMap()
    }
    val apps = pm.getInstalledApplications(0)
    apps.map { ai ->
        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            ai.packageName
        }
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val size = if (useStats) {
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
        InstalledApp(label, ai.packageName, size, system, lastUsedMap[ai.packageName] ?: 0L, match)
    }.sortedByDescending { unusedDays(it.lastUsed) } // forgotten (never / longest-unused) first
}

private suspend fun describeAppWithClaude(apiKey: String, name: String, pkg: String): String =
    withContext(Dispatchers.IO) {
        try {
            val system = "You identify Android apps for a non-technical user cleaning up their phone. " +
                "Given an app name, reply in 2-3 short plain sentences: what the app does; whether it typically " +
                "needs a paid subscription to be useful (count auto-billing free trials as paid); and name the single " +
                "best free alternative that does the same job (just one). If you are not sure what the app is, say so briefly."
            val body = JSONObject()
                .put("model", "claude-opus-4-8")
                .put("max_tokens", 400)
                .put("system", system)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("content", "App name: \"$name\" (package $pkg). What is this app?"),
                    ),
                )
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            claudeHttpClient.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext "Couldn't identify it (error ${resp.code})."
                }
                val content = JSONObject(s).getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
                sb.toString().ifBlank { "No description available." }
            }
        } catch (e: Exception) {
            "Couldn't reach Claude: ${e.message ?: "network error"}"
        }
    }

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showSystem by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("forgotten") } // forgotten | never | all
    val claudeDesc = remember { mutableStateMapOf<String, String>() }
    val claudeBusy = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(reloadKey) {
        loading = true
        usage = hasUsageAccess(context)
        apps = loadApps(context)
        loading = false
    }

    val visible = apps
        .filter { showSystem || !it.system }
        .filter {
            when (filter) {
                "never" -> it.lastUsed <= 0
                "forgotten" -> unusedDays(it.lastUsed) >= 30
                else -> true
            }
        }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Apps you forgot about", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "The apps you haven't opened in a long time show first, so you can decide what to delete. Each one tells you what it is, if it costs a subscription, and a free alternative.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (!usage) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "To see when each app was last opened and how big it is, Clean AF needs Usage Access (a one-time system toggle).",
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
            FilterChip(selected = filter == "forgotten", onClick = { filter = "forgotten" }, label = { Text("Forgotten") })
            Spacer(Modifier.height(0.dp))
            FilterChip(selected = filter == "never", onClick = { filter = "never" }, label = { Text("Never opened") }, modifier = Modifier.padding(start = 6.dp))
            FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All") }, modifier = Modifier.padding(start = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("System apps") })
            OutlinedButton(onClick = { reloadKey++ }, modifier = Modifier.padding(start = 8.dp)) { Text("Refresh") }
        }
        Spacer(Modifier.height(6.dp))
        Text("${visible.size} apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))

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
                                Text(
                                    lastUsedLabel(app.lastUsed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (unusedDays(app.lastUsed) >= 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                        if (app.ref == null) {
                            claudeDesc[app.pkg]?.let { d ->
                                Spacer(Modifier.height(6.dp))
                                Text(d, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")),
                                )
                            }) { Text("Uninstall") }

                            if (app.ref == null && claudeDesc[app.pkg] == null) {
                                OutlinedButton(
                                    onClick = {
                                        val key = context.getSharedPreferences("cleanaf", Context.MODE_PRIVATE)
                                            .getString("apikey", "").orEmpty()
                                        if (key.isBlank()) {
                                            claudeDesc[app.pkg] = "Add your Anthropic key in the Assistant tab to identify unknown apps."
                                        } else {
                                            claudeBusy[app.pkg] = true
                                            scope.launch {
                                                val d = describeAppWithClaude(key, app.name, app.pkg)
                                                claudeDesc[app.pkg] = d
                                                claudeBusy[app.pkg] = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                    enabled = claudeBusy[app.pkg] != true,
                                ) { Text(if (claudeBusy[app.pkg] == true) "Asking…" else "What is this?") }
                            }
                        }
                    }
                }
            }
        }
    }
}
