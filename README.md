# Alalqami Agent

**Repository:** `Mtzallqmy/Agalqami`  
**Android application ID:** `com.alalqami.agent`  
**Version:** `0.1.3`

منصة وكلاء ذكاء اصطناعي تعمل من Android مع Agent Runtime مستقل وبنية قابلة لإضافة الأدوات وMCP والـComputer Runtime لاحقًا.

## ما يعمل الآن

- إنشاء Agent عبر REST.
- إرسال مهمة إلى Agent.
- Agent Runtime مستقل.
- بث أحداث النص عبر WebSocket.
- Android Native بـ Kotlin + Jetpack Compose.
- Android 8.0+ (`minSdk 26`).
- `arm64-v8a` للأجهزة الحقيقية و`x86_64` للمحاكيات.
- شاشة داخل Android لاختيار المزود والموديل وBase URL وإدخال API Key.
- حفظ API Key محليًا مشفّرًا باستخدام Android Keystore.
- الـBackend لا يعيد المفتاح في أي استجابة؛ يعيد فقط `hasApiKey`.
- Mock Provider للاختبار بدون مفتاح.
- OpenAI Native عبر Responses API.
- xAI / Grok عبر Responses API.
- OpenRouter عبر OpenAI-compatible Chat Completions.
- مزود عام OpenAI-compatible لأي endpoint يدعم `/chat/completions`.

## تشغيل الـBackend

```bash
cp .env.example .env
npm install
npm run dev:api
```

الافتراضي:

```bash
AI_PROVIDER=mock
```

### OpenAI Native

```bash
export AI_PROVIDER=openai
export OPENAI_API_KEY=YOUR_KEY
export OPENAI_MODEL=gpt-5.6-sol
npm run dev:api
```

يستخدم:

```text
POST https://api.openai.com/v1/responses
```

مع SSE Streaming وأحداث `response.output_text.delta`.

### xAI / Grok

```bash
export AI_PROVIDER=xai
export XAI_API_KEY=YOUR_KEY
export XAI_MODEL=grok-4.6
npm run dev:api
```

### OpenRouter

```bash
export AI_PROVIDER=openrouter
export OPENROUTER_API_KEY=YOUR_KEY
export OPENROUTER_MODEL=openai/gpt-5.4
npm run dev:api
```

### OpenAI-compatible

```bash
export AI_PROVIDER=openai-compatible
export OPENAI_COMPAT_BASE_URL=http://localhost:1234/v1
export OPENAI_COMPAT_API_KEY=
export OPENAI_COMPAT_MODEL=your-model
npm run dev:api
```

`OPENAI_COMPAT_API_KEY` اختياري للخوادم المحلية التي لا تتطلب Bearer token.

## Provider Settings API

Android يستخدم هذه الواجهات لتغيير المزود أثناء التشغيل:

```text
GET /settings/provider
PUT /settings/provider
```

مثال:

```json
{
  "provider": "openai",
  "model": "gpt-5.6-sol",
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "..."
}
```

المفتاح لا يظهر في استجابة `GET` أو `PUT`. إعدادات المزود المرسلة من Android تحفظ حاليًا في ذاكرة الـBackend فقط، لذلك عند إعادة تشغيل الخادم يعود إلى إعدادات البيئة `.env`. سيتم نقل الأسرار لاحقًا إلى Secret Vault دائم.

## API

```text
GET  /health
GET  /settings/provider
PUT  /settings/provider
GET  /agents
POST /agents
POST /agents/{id}/messages
WS   /events
```

## أمان مفاتيح API

- Android يشفر المفتاح محليًا باستخدام AES/GCM ومفتاح محفوظ في Android Keystore.
- تم تعطيل Android Backup للتطبيق حتى لا تُنسخ ciphertext preferences بدون مفتاح Keystore.
- لا يتم إرسال المفتاح في الرسائل أو transcript.
- بيئة التطوير الحالية تستخدم HTTP مع `10.0.2.2`. قبل أي نشر فعلي يجب تحويل API وWebSocket إلى `HTTPS/WSS`.

## المرحلة التالية

Tool Registry + Approval Policy + Docker Computer Service، ثم MCP وBrowser وAutomations وSubagents.
