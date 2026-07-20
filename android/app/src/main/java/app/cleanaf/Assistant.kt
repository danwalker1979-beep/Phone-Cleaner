package app.cleanaf

import android.content.Context
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMsg(val role: String, val text: String)

private const val SYSTEM_PROMPT =
    "You are the built-in assistant for Clean AF, an Android phone-cleanup app with three tabs: " +
        "Analyze (scans the phone for duplicate photos, videos and audio and lets the user delete the extras), " +
        "Apps (lists installed apps by size with subscription info and free alternatives, and can uninstall), " +
        "and Assistant (this chat). Help the user tidy their phone and answer questions clearly and briefly. " +
        "Never claim to take actions you cannot; guide the user to the right tab or setting."

private val httpClient by lazy {
    OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}

private suspend fun askClaude(key: String, history: List<ChatMsg>): String =
    withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
            body.put("model", "claude-opus-4-8")
            body.put("max_tokens", 1024)
            body.put("system", SYSTEM_PROMPT)
            val msgs = JSONArray()
            history.forEach { m -> msgs.put(JSONObject().put("role", m.role).put("content", m.text)) }
            body.put("messages", msgs)
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext "Error ${resp.code}. " +
                        (try { JSONObject(s).getJSONObject("error").getString("message") } catch (e: Exception) { s.take(200) })
                }
                val content = JSONObject(s).getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
                sb.toString().ifBlank { "(no reply)" }
            }
        } catch (e: Exception) {
            "Couldn't reach Claude: ${e.message ?: "network error"}"
        }
    }

@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cleanaf", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(prefs.getString("apikey", "").orEmpty()) }
    var keyInput by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMsg>() }

    if (apiKey.isBlank()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Claude assistant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add your Anthropic API key to chat with Claude inside the app. The key is stored only on this phone, and messages go directly from your phone to Anthropic — usage is billed to your own account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Anthropic API key (sk-ant-…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    prefs.edit().putString("apikey", keyInput.trim()).apply()
                    apiKey = keyInput.trim()
                },
                enabled = keyInput.isNotBlank(),
            ) { Text("Save key") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Get a key at console.anthropic.com. You can remove it any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Claude assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                prefs.edit().remove("apikey").apply()
                apiKey = ""
                messages.clear()
            }) { Text("Remove key") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { m ->
                val mine = m.role == "user"
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            if (mine) "You" else "Claude",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask about cleaning your phone…") },
                modifier = Modifier.weight(1f),
                enabled = !sending,
            )
            Spacer(Modifier.height(0.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isBlank()) return@Button
                    messages.add(ChatMsg("user", text))
                    input = ""
                    sending = true
                    scope.launch {
                        val reply = askClaude(apiKey, messages.toList())
                        messages.add(ChatMsg("assistant", reply))
                        sending = false
                    }
                },
                enabled = !sending && input.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(if (sending) "…" else "Send") }
        }
    }
}
