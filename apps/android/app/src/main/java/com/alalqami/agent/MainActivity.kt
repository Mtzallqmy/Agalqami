package com.alalqami.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private data class AgentUi(val id: String, val name: String, val status: String)

private data class ProviderSettingsUi(
    val provider: String = "mock",
    val model: String = "mock-model",
    val baseUrl: String = "",
    val hasApiKey: Boolean = false
)

private enum class AppScreen { AGENTS, CHAT, SETTINGS }

private val providerOptions = listOf(
    "openai" to "OpenAI · Responses API",
    "openrouter" to "OpenRouter",
    "xai" to "xAI · Grok",
    "openai-compatible" to "OpenAI-compatible",
    "mock" to "Mock · بدون API"
)

private fun providerDefaults(provider: String): Pair<String, String> = when (provider) {
    "openai" -> "gpt-5.6-sol" to "https://api.openai.com/v1"
    "openrouter" -> "openai/gpt-5.4" to "https://openrouter.ai/api/v1"
    "xai" -> "grok-4.6" to "https://api.x.ai/v1"
    "openai-compatible" -> "gpt-5" to ""
    else -> "mock-model" to ""
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AlalqamiAgentApp() } }
    }
}

@Composable
private fun AlalqamiAgentApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val client = remember { OkHttpClient() }
    val secretStore = remember { ProviderSecretStore(context) }

    var screen by remember { mutableStateOf(AppScreen.AGENTS) }
    var agents by remember { mutableStateOf(emptyList<AgentUi>()) }
    var selected by remember { mutableStateOf<AgentUi?>(null) }
    val selectedState = rememberUpdatedState(selected)

    var transcript by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }

    var providerSettings by remember { mutableStateOf(ProviderSettingsUi()) }
    var editProvider by remember { mutableStateOf("mock") }
    var editModel by remember { mutableStateOf("mock-model") }
    var editBaseUrl by remember { mutableStateOf("") }
    var editApiKey by remember { mutableStateOf("") }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var savingSettings by remember { mutableStateOf(false) }

    fun openSettings() {
        editProvider = providerSettings.provider
        editModel = providerSettings.model
        editBaseUrl = providerSettings.baseUrl
        editApiKey = secretStore.get(providerSettings.provider)
        settingsMessage = null
        screen = AppScreen.SETTINGS
    }

    fun connectEvents() {
        val request = Request.Builder().url("${BuildConfig.WS_BASE_URL}/events").build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { connected = true }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { connected = false }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { connected = false }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (event.optString("agentId") != selectedState.value?.id) return

                scope.launch {
                    when (event.optString("type")) {
                        "agent.message.delta" -> transcript += event.optString("delta")
                        "agent.started" -> transcript = ""
                        "agent.failed" -> transcript += "\n\nخطأ: ${event.optString("error")}"
                    }
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

    suspend fun refreshProviderSettings() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${BuildConfig.API_BASE_URL}/settings/provider").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use
            val o = JSONObject(response.body.string())
            val settings = ProviderSettingsUi(
                provider = o.getString("provider"),
                model = o.getString("model"),
                baseUrl = o.optString("baseUrl"),
                hasApiKey = o.optBoolean("hasApiKey")
            )
            withContext(Dispatchers.Main) { providerSettings = settings }
        }
    }

    suspend fun createAgent() = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("name", "مساعد Android")
            .put(
                "instructions",
                "أنت وكيل ذكاء اصطناعي دقيق ومفيد. أجب بالعربية ما لم يطلب المستخدم غير ذلك."
            )
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

    suspend fun saveProviderSettings(): ProviderSettingsUi = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("provider", editProvider)
            .put("model", editModel.trim())

        if (editBaseUrl.isNotBlank()) payload.put("baseUrl", editBaseUrl.trim())
        if (editApiKey.isNotBlank()) payload.put("apiKey", editApiKey.trim())

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/settings/provider")
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(raw).optString("error") }.getOrNull()
                throw IllegalStateException(error?.ifBlank { null } ?: "تعذر حفظ إعدادات المزود")
            }
            val o = JSONObject(raw)
            ProviderSettingsUi(
                provider = o.getString("provider"),
                model = o.getString("model"),
                baseUrl = o.optString("baseUrl"),
                hasApiKey = o.optBoolean("hasApiKey")
            )
        }
    }

    LaunchedEffect(Unit) {
        connectEvents()
        refreshAgents()
        refreshProviderSettings()
    }

    when (screen) {
        AppScreen.AGENTS -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Alalqami Agent") },
                        actions = {
                            Text(if (connected) "●" else "○")
                            TextButton(onClick = ::openSettings) { Text("المزود") }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(onClick = { scope.launch { createAgent() } }) { Text("+") }
                }
            ) { padding ->
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    item {
                        ListItem(
                            headlineContent = { Text("المزود الحالي") },
                            supportingContent = {
                                Text("${providerSettings.provider} · ${providerSettings.model}")
                            }
                        )
                        HorizontalDivider()
                    }
                    items(agents) { agent ->
                        ListItem(
                            headlineContent = { Text(agent.name) },
                            supportingContent = { Text(agent.status) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        selected = agent
                                        transcript = ""
                                        screen = AppScreen.CHAT
                                    }
                                ) { Text("فتح") }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        AppScreen.CHAT -> {
            val agent = selected
            if (agent == null) {
                screen = AppScreen.AGENTS
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(agent.name) },
                            navigationIcon = {
                                TextButton(
                                    onClick = {
                                        selected = null
                                        screen = AppScreen.AGENTS
                                    }
                                ) { Text("رجوع") }
                            },
                            actions = {
                                TextButton(onClick = ::openSettings) { Text("المزود") }
                            }
                        )
                    }
                ) { padding ->
                    Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                        Text(
                            "${providerSettings.provider} · ${providerSettings.model}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("استجابة الوكيل", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            transcript.ifBlank { "أرسل مهمة لبدء أول Run." },
                            Modifier.weight(1f)
                        )
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

        AppScreen.SETTINGS -> {
            ProviderSettingsScreen(
                provider = editProvider,
                model = editModel,
                baseUrl = editBaseUrl,
                apiKey = editApiKey,
                serverHasApiKey = providerSettings.provider == editProvider && providerSettings.hasApiKey,
                saving = savingSettings,
                message = settingsMessage,
                onProviderChange = { provider ->
                    editProvider = provider
                    if (provider == providerSettings.provider) {
                        editModel = providerSettings.model
                        editBaseUrl = providerSettings.baseUrl
                    } else {
                        val defaults = providerDefaults(provider)
                        editModel = defaults.first
                        editBaseUrl = defaults.second
                    }
                    editApiKey = secretStore.get(provider)
                    settingsMessage = null
                },
                onModelChange = { editModel = it },
                onBaseUrlChange = { editBaseUrl = it },
                onApiKeyChange = { editApiKey = it },
                onBack = {
                    screen = if (selected == null) AppScreen.AGENTS else AppScreen.CHAT
                },
                onSave = {
                    scope.launch {
                        savingSettings = true
                        settingsMessage = null
                        runCatching { saveProviderSettings() }
                            .onSuccess { saved ->
                                providerSettings = saved
                                if (editApiKey.isNotBlank()) {
                                    secretStore.put(saved.provider, editApiKey)
                                }
                                settingsMessage = "تم حفظ المزود وتفعيله."
                                refreshAgents()
                            }
                            .onFailure { error ->
                                settingsMessage = error.message ?: "تعذر حفظ إعدادات المزود."
                            }
                        savingSettings = false
                    }
                }
            )
        }
    }
}

@Composable
private fun ProviderSettingsScreen(
    provider: String,
    model: String,
    baseUrl: String,
    apiKey: String,
    serverHasApiKey: Boolean,
    saving: Boolean,
    message: String?,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مزود الذكاء الاصطناعي") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("رجوع") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("اختر المزود", style = MaterialTheme.typography.titleMedium)
            }

            items(providerOptions) { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProviderChange(option.first) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(
                        selected = provider == option.first,
                        onClick = { onProviderChange(option.first) }
                    )
                    Text(option.second, modifier = Modifier.padding(top = 12.dp))
                }
            }

            item {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text("Model") },
                    singleLine = true,
                    enabled = provider != "mock",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (provider != "mock") {
                item {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = onBaseUrlChange,
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("API Key") },
                        supportingText = {
                            Text(
                                if (serverHasApiKey && apiKey.isBlank()) {
                                    "يوجد مفتاح مفعّل على الخادم. اترك الحقل فارغًا للاحتفاظ به."
                                } else {
                                    "يُحفظ محليًا مشفّرًا باستخدام Android Keystore."
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = onSave,
                    enabled = !saving && (provider == "mock" || model.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (saving) "جارٍ الحفظ..." else "حفظ وتفعيل")
                }
            }

            if (!message.isNullOrBlank()) {
                item { Text(message) }
            }

            item {
                Text(
                    "ملاحظة: اتصال التطوير الحالي يستخدم HTTP محليًا. في النشر الفعلي يجب استخدام HTTPS قبل إرسال أي مفتاح API إلى الـBackend.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
