package app.cleanaf

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val JUNK_EXTENSIONS = setOf(
    "tmp", "temp", "log", "crdownload", "part", "partial", "bak", "old", "cache", "dmp",
)
private val JUNK_NAMES = setOf("thumbs.db", ".ds_store", ".thumbnails")
private val JUNK_DIRS = setOf(".thumbnails", "cache", "tmp", "temp", "log", "logs")

data class JunkItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val path: String,
    val reason: String,
    val isDir: Boolean,
)

private fun junkReason(f: TreeFile): String? {
    val lower = f.name.lowercase()
    val ext = lower.substringAfterLast('.', "")
    val parent = f.path.substringBeforeLast('/', "").substringAfterLast('/').lowercase()
    return when {
        f.size == 0L -> "Empty file"
        lower in JUNK_NAMES -> "System clutter"
        ext in JUNK_EXTENSIONS -> "Temporary file"
        parent in JUNK_DIRS -> "Leftover cache"
        else -> null
    }
}

private suspend fun scanJunk(
    context: android.content.Context,
    treeUri: Uri,
    onProgress: (Int) -> Unit,
): List<JunkItem> = withContext(Dispatchers.IO) {
    val items = ArrayList<JunkItem>()
    walkTree(
        context,
        treeUri,
        onFile = { f ->
            val reason = junkReason(f)
            if (reason != null) {
                items.add(JunkItem(f.uri, f.name, f.size, f.path, reason, false))
                onProgress(items.size)
            }
        },
        onEmptyDir = { uri, path ->
            items.add(JunkItem(uri, path.substringAfterLast('/'), 0, path, "Empty folder", true))
            onProgress(items.size)
        },
    )
    items.sortedByDescending { it.size }
}

@Composable
fun LeftoversScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var junk by remember { mutableStateOf<List<JunkItem>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    val selected: SnapshotStateList<Uri> = remember { mutableStateListOf<Uri>() }

    fun runScan(treeUri: Uri) {
        scope.launch {
            scanning = true
            scanned = false
            junk = emptyList()
            selected.clear()
            val result = scanJunk(context, treeUri) { n -> statusText = "Found $n so far…" }
            junk = result
            result.forEach { selected.add(it.uri) }
            scanning = false
            scanned = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
            }
            folder = treeDisplayName(uri)
            runScan(uri)
        }
    }

    fun doDelete() {
        scope.launch {
            val toDelete = selected.toList()
            withContext(Dispatchers.IO) { toDelete.forEach { deleteDocument(context, it) } }
            junk = junk.filterNot { it.uri in toDelete }
            selected.clear()
        }
    }

    val reclaim = junk.filter { selected.contains(it.uri) }.sumOf { it.size }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${selected.size} items?") },
            text = { Text("This permanently deletes the selected leftover files and empty folders (${fmtSize(reclaim)}). Only the items you've ticked are removed.") },
            confirmButton = { TextButton(onClick = { confirm = false; doDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Leftover junk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pick a folder and Clean AF looks for junk left behind — temporary files, empty files, stray cache files and empty folders — so you can clear them out.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (!scanning) {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (folder == null) "Choose a folder to scan" else "Choose another folder")
            }
            folder?.let {
                Spacer(Modifier.height(6.dp))
                Text("Scanned: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text("  $statusText", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))

        if (scanned && junk.isNotEmpty()) {
            Text(
                "${selected.size} items · ${fmtSize(reclaim)} to reclaim",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (scanned && junk.isEmpty() && !scanning) {
            Text("No leftover junk found in that folder — nice and clean.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(junk) { j ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected.contains(j.uri),
                            onCheckedChange = { checked -> if (checked) selected.add(j.uri) else selected.remove(j.uri) },
                        )
                        Column(Modifier.padding(start = 6.dp).weight(1f)) {
                            Text(j.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${j.reason}${if (!j.isDir && j.size > 0) " · ${fmtSize(j.size)}" else ""} · ${j.path}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (scanned && selected.size > 0) {
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete ${selected.size} selected (${fmtSize(reclaim)})")
            }
        }
    }
}
