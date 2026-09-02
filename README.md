# Alalqami Agent

**Repository:** `Mtzallqmy/Agalqami`  
**Android application ID:** `com.alalqami.agent`  
**Version:** `0.1.1`

نسخة أولية لمنصة وكلاء ذكاء اصطناعي تعمل من Android، مستوحاة معماريًا من فكرة AI employee + remote computer، لكن بكود مستقل.

## ما يعمل الآن

- إنشاء Agent عبر REST.
- إرسال مهمة إلى Agent.
- Agent Runtime مستقل.
- Mock Provider للاختبار بدون أي مفتاح API.
- xAI Responses API مع Grok عبر `XAI_API_KEY`.
- OpenRouter عبر OpenAI-compatible Chat Completions.
- مزود عام `OpenAI-compatible` لأي endpoint متوافق مع `/chat/completions`.
- بث أحداث النص عبر WebSocket.
- Android Native skeleton بـ Kotlin + Jetpack Compose.

## تشغيل الـBackend

```bash
cp .env.example .env
npm install
npm run dev:api
```

الافتراضي `AI_PROVIDER=mock`.

لتفعيل Grok:

```bash
export AI_PROVIDER=xai
export XAI_API_KEY=YOUR_KEY
export XAI_MODEL=grok-4.6
npm run dev:api
```

لتفعيل OpenRouter:

```bash
export AI_PROVIDER=openrouter
export OPENROUTER_API_KEY=YOUR_KEY
export OPENROUTER_MODEL=openai/gpt-5.4
npm run dev:api
```

لتفعيل أي OpenAI-compatible endpoint:

```bash
export AI_PROVIDER=openai-compatible
export OPENAI_COMPAT_BASE_URL=https://api.openai.com/v1
export OPENAI_COMPAT_API_KEY=YOUR_KEY
export OPENAI_COMPAT_MODEL=gpt-5
npm run dev:api
```

`OPENAI_COMPAT_API_KEY` اختياري للخوادم المحلية التي لا تتطلب Bearer token.

API:

```text
GET  /health
GET  /agents
POST /agents
POST /agents/{id}/messages
WS   /events
```

## اختبار سريع

```bash
curl -X POST http://localhost:8787/agents \
  -H 'content-type: application/json' \
  -d '{"name":"مساعدي","instructions":"أنت وكيل دقيق ومفيد"}'
```

ثم أرسل رسالة إلى `agentId` الناتج:

```bash
curl -X POST http://localhost:8787/agents/AGENT_ID/messages \
  -H 'content-type: application/json' \
  -d '{"message":"مرحبا، عرفني بنفسك"}'
```

## المرحلة التالية

Tool Registry + Approval Policy + Docker Computer Service، ثم MCP وBrowser وAutomations وSubagents.
