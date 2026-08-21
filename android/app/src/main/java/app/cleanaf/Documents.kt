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
import androidx.compose.material3.Divider
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

private val DOC_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf",
    "odt", "ods", "odp", "epub", "csv", "pages", "numbers", "key", "md",
)

private fun isDocument(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in DOC_EXTENSIONS
}

data class DocItem(val uri: Uri, val name: String, val size: Long, val path: String, val modified: Long, val keep: Boolean = false)

data class DocGroup(val items: List<DocItem>)

private suspend fun scanDocDuplicates(
    context: android.content.Context,
    treeUri: Uri,
    onProgress: (Int) -> Unit,
): List<DocGroup> = withContext(Dispatchers.IO) {
    val found = ArrayList<TreeFile>()
    walkTree(context, treeUri, onFile = { f ->
        if (f.size > 0 && isDocument(f.name)) {
            found.add(f)
            onProgress(found.size)
        }
    })
    val byKey = HashMap<String, MutableList<DocItem>>()
    found.forEach { f ->
        val key = try {
            contentFileKey(context, f.uri, f.size)
        } catch (e: Exception) {
            "s${f.size}-${f.name}"
        }
        byKey.getOrPut(key) { ArrayList() }.add(DocItem(f.uri, f.name, f.size, f.path, f.modified))
    }
    byKey.values
        .filter { it.size > 1 }
        .map { group ->
            // Keep the most recently modified copy.
            val sorted = group.sortedByDescending { it.modified }
            DocGroup(sorted.mapIndexed { idx, it -> it.copy(keep = idx == 0) })
        }
        .sortedByDescending { g -> g.items.drop(1).sumOf { it.size } }
}

@Composable
fun DocumentsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<DocGroup>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    val selected: SnapshotStateList<Uri> = remember { mutableStateListOf<Uri>() }

    fun runScan(treeUri: Uri) {
        scope.launch {
            scanning = true
            scanned = false
            groups = emptyList()
            selected.clear()
            val result = scanDocDuplicates(context, treeUri) { n ->
                statusText = "Reading document $n…"
            }
            groups = result
            result.forEach { g -> g.items.filter { !it.keep }.forEach { selected.add(it.uri) } }
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
            groups = groups.map { g -> DocGroup(g.items.filterNot { it.uri in toDelete }) }
                .filter { it.items.size > 1 }
            selected.clear()
        }
    }

    val reclaim = groups.flatMap { it.items }.filter { selected.contains(it.uri) }.sumOf { it.size }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${selected.size} documents?") },
            text = { Text("This permanently deletes the selected duplicate copies (${fmtSize(reclaim)}). The copy marked KEEP in each group stays.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; doDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Duplicate documents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pick a folder (like Download or Documents) and Clean AF finds duplicate PDFs, Word, Excel and other documents inside it. It keeps the newest copy of each.",
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

        if (scanned && groups.isNotEmpty()) {
            Text(
                "${selected.size} duplicates · ${fmtSize(reclaim)} to reclaim",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (scanned && groups.isEmpty() && !scanning) {
            Text("No duplicate documents found in that folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(groups) { g ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${g.items.size} copies · saves ${fmtSize(g.items.drop(1).sumOf { it.size })}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Divider(Modifier.padding(vertical = 6.dp))
                        g.items.forEach { it ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                if (it.keep) {
                                    Text("KEEP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                } else {
                                    Checkbox(
                                        checked = selected.contains(it.uri),
                                        onCheckedChange = { checked ->
                                            if (checked) selected.add(it.uri) else selected.remove(it.uri)
                                        },
                                    )
                                }
                                Column(Modifier.padding(start = 6.dp)) {
                                    Text(it.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${fmtSize(it.size)} · ${it.path}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
