package com.alalqami.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private data class AgentUi(val id: String, val name: String, val status: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AlalqamiAgentApp() } }
    }
}

@Composable
private fun AlalqamiAgentApp() {
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }
    var agents by remember { mutableStateOf(emptyList<AgentUi>()) }
    var selected by remember { mutableStateOf<AgentUi?>(null) }
    var transcript by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }

    fun connectEvents() {
        val request = Request.Builder().url("${BuildConfig.WS_BASE_URL}/events").build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { connected = true }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected = false }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { connected = false }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (event.optString("agentId") != selected?.id) return
                when (event.optString("type")) {
                    "agent.message.delta" -> transcript += event.optString("delta")
                    "agent.started" -> transcript = ""
                }
            }
        })
    }

    suspend fun refreshAgents() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${BuildConfig.API_BASE_URL}/agents").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use
            val array = JSONArray(response.body.string())
            val list = buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(AgentUi(o.getString("id"), o.getString("name"), o.getString("status")))
                }
            }
            withContext(Dispatchers.Main) { agents = list }
        }
    }

    suspend fun createAgent() = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("name", "مساعد Android")
            .put("instructions", "أنت وكيل ذكاء اصطناعي دقيق ومفيد. أجب بالعربية ما لم يطلب المستخدم غير ذلك.")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${BuildConfig.API_BASE_URL}/agents").post(body).build()
        client.newCall(request).execute().close()
        refreshAgents()
    }

    suspend fun sendMessage(agent: AgentUi, message: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("message", message).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/agents/${agent.id}/messages")
            .post(body)
            .build()
        client.newCall(request).execute().close()
    }

    LaunchedEffect(Unit) {
        connectEvents()
        refreshAgents()
    }

    if (selected == null) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Alalqami Agent") }, actions = { Text(if (connected) "●" else "○") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { scope.launch { createAgent() } }) { Text("+") }
            }
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(agents) { agent ->
                    ListItem(
                        headlineContent = { Text(agent.name) },
                        supportingContent = { Text(agent.status) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingContent = { TextButton(onClick = { selected = agent; transcript = "" }) { Text("فتح") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    } else {
        val agent = selected!!
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(agent.name) },
                    navigationIcon = { TextButton(onClick = { selected = null }) { Text("رجوع") } }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                Text("استجابة الوكيل", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(transcript.ifBlank { "أرسل مهمة لبدء أول Run." }, Modifier.weight(1f))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("المهمة") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val message = input.trim()
                        if (message.isNotEmpty()) {
                            input = ""
                            scope.launch { sendMessage(agent, message) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إرسال") }
            }
        }
    }
}
