import type { AgentDefinition, AgentEvent, ProviderKind } from "@alalqami/protocol";

export interface ModelRequest {
  instructions: string;
  input: string;
  model: string;
}

export interface ModelProvider {
  stream(request: ModelRequest): AsyncIterable<string>;
}

export type EventSink = (event: AgentEvent) => void;
export type ProviderEnv = Record<string, string | undefined>;

const id = (prefix: string) => `${prefix}_${crypto.randomUUID()}`;
const now = () => new Date().toISOString();
const trimSlash = (value: string) => value.replace(/\/+$/, "");

async function* readSseData(response: Response): AsyncIterable<string> {
  if (!response.body) throw new Error("Streaming response has no body");

  const decoder = new TextDecoder();
  let buffer = "";

  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true }).replace(/\r\n/g, "\n");
    let boundary = buffer.indexOf("\n\n");

    while (boundary >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);

      const data = block
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trim())
        .join("\n");

      if (data) yield data;
      boundary = buffer.indexOf("\n\n");
    }
  }

  const tail = buffer.trim();
  if (tail.startsWith("data:")) yield tail.slice(5).trim();
}

export class MockProvider implements ModelProvider {
  async *stream(request: ModelRequest): AsyncIterable<string> {
    const text = `تم استلام المهمة: ${request.input}\n\nهذه استجابة تجريبية من Alalqami Agent. اختر مزودًا حقيقيًا من إعدادات التطبيق.`;
    for (const word of text.split(/(\s+)/)) {
      await new Promise((resolve) => setTimeout(resolve, 25));
      yield word;
    }
  }
}

export interface ResponsesApiOptions {
  apiKey: string;
  baseUrl: string;
  providerName: string;
}

export class ResponsesApiProvider implements ModelProvider {
  constructor(private readonly options: ResponsesApiOptions) {}

  async *stream(request: ModelRequest): AsyncIterable<string> {
    const response = await fetch(`${trimSlash(this.options.baseUrl)}/responses`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.options.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: request.model,
        instructions: request.instructions,
        input: request.input,
        stream: true,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      throw new Error(
        `${this.options.providerName} request failed (${response.status}): ${body.slice(0, 500)}`,
      );
    }

    for await (const payload of readSseData(response)) {
      if (payload === "[DONE]") break;

      let event: { type?: string; delta?: string; error?: { message?: string } };
      try {
        event = JSON.parse(payload) as typeof event;
      } catch {
        continue;
      }

      if (event.error?.message) throw new Error(event.error.message);
      if (event.type === "response.output_text.delta" && typeof event.delta === "string") {
        yield event.delta;
      }
    }
  }
}

export class OpenAIResponsesProvider extends ResponsesApiProvider {
  constructor(apiKey: string, baseUrl = "https://api.openai.com/v1") {
    super({ apiKey, baseUrl, providerName: "OpenAI" });
  }
}

export class XaiResponsesProvider extends ResponsesApiProvider {
  constructor(apiKey: string, baseUrl = "https://api.x.ai/v1") {
    super({ apiKey, baseUrl, providerName: "xAI" });
  }
}

export interface OpenAICompatibleOptions {
  baseUrl: string;
  apiKey?: string;
  headers?: Record<string, string>;
  providerName?: string;
}

/**
 * Generic OpenAI-compatible Chat Completions provider.
 * Works with services that expose POST {baseUrl}/chat/completions and SSE streaming.
 */
export class OpenAICompatibleChatProvider implements ModelProvider {
  constructor(private readonly options: OpenAICompatibleOptions) {}

  async *stream(request: ModelRequest): AsyncIterable<string> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...this.options.headers,
    };
    if (this.options.apiKey) headers.Authorization = `Bearer ${this.options.apiKey}`;

    const response = await fetch(`${trimSlash(this.options.baseUrl)}/chat/completions`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        model: request.model,
        messages: [
          { role: "system", content: request.instructions },
          { role: "user", content: request.input },
        ],
        stream: true,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      const provider = this.options.providerName ?? "OpenAI-compatible";
      throw new Error(`${provider} request failed (${response.status}): ${body.slice(0, 500)}`);
    }

    for await (const payload of readSseData(response)) {
      if (payload === "[DONE]") break;

      let event: {
        error?: { message?: string };
        choices?: Array<{ delta?: { content?: string | Array<{ type?: string; text?: string }> } }>;
      };
      try {
        event = JSON.parse(payload) as typeof event;
      } catch {
        continue;
      }

      if (event.error?.message) throw new Error(event.error.message);

      const content = event.choices?.[0]?.delta?.content;
      if (typeof content === "string") {
        yield content;
      } else if (Array.isArray(content)) {
        for (const part of content) {
          if ((part.type === "text" || !part.type) && typeof part.text === "string") {
            yield part.text;
          }
        }
      }
    }
  }
}

export class OpenRouterProvider extends OpenAICompatibleChatProvider {
  constructor(
    apiKey: string,
    baseUrl = "https://openrouter.ai/api/v1",
    appUrl?: string,
    appName = "Alalqami Agent",
  ) {
    super({
      apiKey,
      baseUrl,
      providerName: "OpenRouter",
      headers: {
        ...(appUrl ? { "HTTP-Referer": appUrl } : {}),
        ...(appName ? { "X-Title": appName } : {}),
      },
    });
  }
}

export class AgentRuntime {
  constructor(private readonly provider: ModelProvider) {}

  async run(agent: AgentDefinition, runId: string, input: string, emit: EventSink): Promise<void> {
    const base = () => ({ eventId: id("evt"), runId, agentId: agent.id, timestamp: now() });
    emit({ ...base(), type: "agent.started" });

    try {
      let fullText = "";
      for await (const delta of this.provider.stream({
        instructions: agent.instructions,
        input,
        model: agent.model,
      })) {
        fullText += delta;
        emit({ ...base(), type: "agent.message.delta", delta });
      }
      emit({ ...base(), type: "agent.message.completed", text: fullText });
      emit({ ...base(), type: "agent.completed" });
    } catch (error) {
      emit({
        ...base(),
        type: "agent.failed",
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }
}

export type SupportedProvider = ProviderKind;

export function normalizedProviderName(env: ProviderEnv): SupportedProvider {
  const configured = env.AI_PROVIDER?.trim().toLowerCase();

  if (!configured) {
    if (env.OPENAI_API_KEY) return "openai";
    if (env.OPENROUTER_API_KEY) return "openrouter";
    if (env.XAI_API_KEY) return "xai";
    if (env.OPENAI_COMPAT_BASE_URL) return "openai-compatible";
    return "mock";
  }

  if (configured === "openai_compatible" || configured === "compatible") {
    return "openai-compatible";
  }
  if (
    configured === "openai" ||
    configured === "openrouter" ||
    configured === "xai" ||
    configured === "mock"
  ) {
    return configured;
  }
  throw new Error(`Unsupported AI_PROVIDER: ${configured}`);
}

export function defaultModelFromEnv(env: ProviderEnv): string {
  const provider = normalizedProviderName(env);
  switch (provider) {
    case "openai":
      return env.OPENAI_MODEL?.trim() || "gpt-5.6-sol";
    case "xai":
      return env.XAI_MODEL?.trim() || "grok-4.6";
    case "openrouter":
      return env.OPENROUTER_MODEL?.trim() || "openai/gpt-5.4";
    case "openai-compatible":
      return env.OPENAI_COMPAT_MODEL?.trim() || "gpt-5";
    default:
      return env.MOCK_MODEL?.trim() || "mock-model";
  }
}

export function providerBaseUrlFromEnv(env: ProviderEnv): string | undefined {
  switch (normalizedProviderName(env)) {
    case "openai":
      return env.OPENAI_BASE_URL?.trim() || "https://api.openai.com/v1";
    case "xai":
      return env.XAI_BASE_URL?.trim() || "https://api.x.ai/v1";
    case "openrouter":
      return env.OPENROUTER_BASE_URL?.trim() || "https://openrouter.ai/api/v1";
    case "openai-compatible":
      return env.OPENAI_COMPAT_BASE_URL?.trim();
    default:
      return undefined;
  }
}

export function providerHasApiKey(env: ProviderEnv): boolean {
  switch (normalizedProviderName(env)) {
    case "openai":
      return Boolean(env.OPENAI_API_KEY?.trim());
    case "xai":
      return Boolean(env.XAI_API_KEY?.trim());
    case "openrouter":
      return Boolean(env.OPENROUTER_API_KEY?.trim());
    case "openai-compatible":
      return Boolean(env.OPENAI_COMPAT_API_KEY?.trim());
    default:
      return false;
  }
}

export function applyProviderSettings(
  env: ProviderEnv,
  input: { provider: SupportedProvider; model?: string; baseUrl?: string; apiKey?: string },
): ProviderEnv {
  const next: ProviderEnv = { ...env, AI_PROVIDER: input.provider };
  const model = input.model?.trim();
  const baseUrl = input.baseUrl?.trim();
  const apiKey = input.apiKey?.trim();

  switch (input.provider) {
    case "openai":
      if (model) next.OPENAI_MODEL = model;
      if (baseUrl) next.OPENAI_BASE_URL = baseUrl;
      if (apiKey) next.OPENAI_API_KEY = apiKey;
      break;
    case "xai":
      if (model) next.XAI_MODEL = model;
      if (baseUrl) next.XAI_BASE_URL = baseUrl;
      if (apiKey) next.XAI_API_KEY = apiKey;
      break;
    case "openrouter":
      if (model) next.OPENROUTER_MODEL = model;
      if (baseUrl) next.OPENROUTER_BASE_URL = baseUrl;
      if (apiKey) next.OPENROUTER_API_KEY = apiKey;
      break;
    case "openai-compatible":
      if (model) next.OPENAI_COMPAT_MODEL = model;
      if (baseUrl) next.OPENAI_COMPAT_BASE_URL = baseUrl;
      if (apiKey) next.OPENAI_COMPAT_API_KEY = apiKey;
      break;
    case "mock":
      if (model) next.MOCK_MODEL = model;
      break;
  }

  return next;
}

export function createProviderFromEnv(env: ProviderEnv): ModelProvider {
  const provider = normalizedProviderName(env);

  if (provider === "openai") {
    if (!env.OPENAI_API_KEY) throw new Error("AI_PROVIDER=openai requires OPENAI_API_KEY");
    return new OpenAIResponsesProvider(env.OPENAI_API_KEY, env.OPENAI_BASE_URL);
  }

  if (provider === "xai") {
    if (!env.XAI_API_KEY) throw new Error("AI_PROVIDER=xai requires XAI_API_KEY");
    return new XaiResponsesProvider(env.XAI_API_KEY, env.XAI_BASE_URL);
  }

  if (provider === "openrouter") {
    if (!env.OPENROUTER_API_KEY) {
      throw new Error("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY");
    }
    return new OpenRouterProvider(
      env.OPENROUTER_API_KEY,
      env.OPENROUTER_BASE_URL,
      env.OPENROUTER_APP_URL,
      env.OPENROUTER_APP_NAME || "Alalqami Agent",
    );
  }

  if (provider === "openai-compatible") {
    if (!env.OPENAI_COMPAT_BASE_URL) {
      throw new Error("AI_PROVIDER=openai-compatible requires OPENAI_COMPAT_BASE_URL");
    }
    return new OpenAICompatibleChatProvider({
      baseUrl: env.OPENAI_COMPAT_BASE_URL,
      apiKey: env.OPENAI_COMPAT_API_KEY,
      providerName: "OpenAI-compatible",
    });
  }

  return new MockProvider();
}
