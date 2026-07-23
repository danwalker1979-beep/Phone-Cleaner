package app.cleanaf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class DupItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val cat: String,
    val path: String,
    val keep: Boolean = false,
)

data class DupGroup(val cat: String, val items: List<DupItem>)

private fun mediaLabel(type: Int): String = when (type) {
    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "Photos"
    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "Videos"
    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> "Audio"
    else -> "Files"
}

fun fmtSize(bytes: Long): String =
    if (bytes < 1_048_576) "${bytes / 1024} KB" else String.format("%.1f MB", bytes / 1_048_576.0)

private fun hashRegion(ch: java.nio.channels.FileChannel, start: Long, len: Long, md: MessageDigest) {
    ch.position(start)
    val bb = ByteBuffer.allocate(65536)
    var remaining = len
    while (remaining > 0) {
        bb.clear()
        if (bb.limit() > remaining) bb.limit(remaining.toInt())
        val n = ch.read(bb)
        if (n <= 0) break
        bb.flip()
        md.update(bb)
        remaining -= n
    }
}

private fun fileKey(context: Context, uri: Uri, size: Long): String {
    val md = MessageDigest.getInstance("SHA-256")
    val head = 1_048_576L
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val ch = fis.channel
            hashRegion(ch, 0, minOf(head, size), md)
            if (size > 2 * head) hashRegion(ch, size - head, head, md)
        }
    }
    return size.toString() + "-" + md.digest().joinToString("") { "%02x".format(it) }
}

private suspend fun scanDuplicates(
    context: Context,
    onProgress: (Int, Int) -> Unit,
): List<DupGroup> = withContext(Dispatchers.IO) {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.SIZE, FileColumns.MEDIA_TYPE)
    val selection = "${FileColumns.MEDIA_TYPE} IN (?,?,?) AND ${FileColumns.SIZE} > 0"
    val args = arrayOf(
        FileColumns.MEDIA_TYPE_IMAGE.toString(),
        FileColumns.MEDIA_TYPE_VIDEO.toString(),
        FileColumns.MEDIA_TYPE_AUDIO.toString(),
    )
    data class Row(val uri: Uri, val name: String, val size: Long, val cat: String)
    val rows = ArrayList<Row>()
    context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
        val idI = c.getColumnIndexOrThrow(FileColumns._ID)
        val nameI = c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
        val sizeI = c.getColumnIndexOrThrow(FileColumns.SIZE)
        val typeI = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        while (c.moveToNext()) {
            val id = c.getLong(idI)
            rows.add(
                Row(
                    ContentUris.withAppendedId(collection, id),
                    c.getString(nameI) ?: "file",
                    c.getLong(sizeI),
                    mediaLabel(c.getInt(typeI)),
                ),
            )
        }
    }
    val byKey = HashMap<String, MutableList<DupItem>>()
    rows.forEachIndexed { i, r ->
        onProgress(i + 1, rows.size)
        val key = try {
            fileKey(context, r.uri, r.size)
        } catch (e: Exception) {
            "s${r.size}-${r.name}"
        }
        val item = DupItem(r.uri, r.name, r.size, r.cat, r.name)
        byKey.getOrPut(key) { ArrayList() }.add(item)
    }
    byKey.values
        .filter { it.size > 1 }
        .map { group ->
            val sorted = group.sortedByDescending { it.size }
            val marked = sorted.mapIndexed { idx, it -> it.copy(keep = idx == 0) }
            DupGroup(marked.first().cat, marked)
        }
        .sortedByDescending { g -> g.items.drop(1).sumOf { it.size } }
}

@Composable
fun AnalyzeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<DupGroup>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }
    val selected: SnapshotStateList<Uri> = remember { mutableStateListOf<Uri>() }

    fun startScan() {
        scope.launch {
            scanning = true
            groups = emptyList()
            selected.clear()
            val result = scanDuplicates(context) { done, total ->
                progress = if (total == 0) 1f else done.toFloat() / total
                statusText = "Checking $done of $total…"
            }
            groups = result
            // preselect every copy except the one being kept
            result.forEach { g -> g.items.filter { !it.keep }.forEach { selected.add(it.uri) } }
            scanning = false
            scanned = true
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startScan() }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { startScan() }

    fun requestAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms)
    }

    fun deleteSelected() {
        val uris = selected.toList()
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            uris.forEach {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            startScan()
        }
    }

    val removableCount = selected.size
    val reclaim = groups.flatMap { it.items }.filter { selected.contains(it.uri) }.sumOf { it.size }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Analyze for duplicates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap Analyze to check every photo, video and audio file on your phone for duplicates. Nothing leaves the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (scanned && groups.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$removableCount duplicates · ${fmtSize(reclaim)} to reclaim",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (!scanning) {
            Button(onClick = { requestAndScan() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (scanned) "Re-analyze" else "Analyze my phone")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Spacer(Modifier.height(0.dp))
                Text("  $statusText", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))

        if (scanned && groups.isEmpty() && !scanning) {
            Text(
                "No duplicates found — your phone is tidy!",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            items(groups) { g ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${g.cat} · ${g.items.size} copies · saves ${fmtSize(g.items.drop(1).sumOf { it.size })}",
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
                                    Text(fmtSize(it.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (scanned && removableCount > 0) {
            OutlinedButton(onClick = { deleteSelected() }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete $removableCount selected (${fmtSize(reclaim)})")
            }
        }
    }
}
