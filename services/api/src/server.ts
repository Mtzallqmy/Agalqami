import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { AgentRuntime, createProviderFromEnv, defaultModelFromEnv, normalizedProviderName } from "@alalqami/agent-core";
import type { AgentDefinition, AgentEvent, CreateAgentInput, SendMessageInput } from "@alalqami/protocol";

const port = Number(process.env.PORT ?? 8787);
const agents = new Map<string, AgentDefinition>();
const clients = new Set<WebSocket>();
const runtime = new AgentRuntime(createProviderFromEnv(process.env));

const json = (res: ServerResponse, status: number, body: unknown) => {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
};

const readJson = async <T>(req: IncomingMessage): Promise<T> => {
  let raw = "";
  for await (const chunk of req) raw += chunk;
  return JSON.parse(raw || "{}") as T;
};

const broadcast = (event: AgentEvent) => {
  const payload = JSON.stringify(event);
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) client.send(payload);
  }
};

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

    if (req.method === "GET" && url.pathname === "/health") {
      return json(res, 200, { ok: true, service: "alalqami-agent-api" });
    }

    if (req.method === "GET" && url.pathname === "/agents") {
      return json(res, 200, Array.from(agents.values()));
    }

    if (req.method === "POST" && url.pathname === "/agents") {
      const input = await readJson<CreateAgentInput>(req);
      if (!input.name?.trim() || !input.instructions?.trim()) {
        return json(res, 400, { error: "name and instructions are required" });
      }
      const agent: AgentDefinition = {
        id: `agt_${crypto.randomUUID()}`,
        name: input.name.trim(),
        instructions: input.instructions.trim(),
        model: input.model?.trim() || defaultModelFromEnv(process.env),
        status: "idle",
        createdAt: new Date().toISOString(),
      };
      agents.set(agent.id, agent);
      return json(res, 201, agent);
    }

    const messageMatch = url.pathname.match(/^\/agents\/([^/]+)\/messages$/);
    if (req.method === "POST" && messageMatch) {
      const agentId = messageMatch[1]!;
      const agent = agents.get(agentId);
      if (!agent) return json(res, 404, { error: "agent not found" });

      const input = await readJson<SendMessageInput>(req);
      if (!input.message?.trim()) return json(res, 400, { error: "message is required" });

      const runId = `run_${crypto.randomUUID()}`;
      agent.status = "running";
      void runtime.run(agent, runId, input.message.trim(), (event) => {
        broadcast(event);
        if (event.type === "agent.completed") agent.status = "idle";
        if (event.type === "agent.failed") agent.status = "failed";
      });
      return json(res, 202, { runId });
    }

    return json(res, 404, { error: "not found" });
  } catch (error) {
    return json(res, 500, { error: error instanceof Error ? error.message : String(error) });
  }
});

const wss = new WebSocketServer({ noServer: true });
wss.on("connection", (socket) => {
  clients.add(socket);
  socket.on("close", () => clients.delete(socket));
  socket.send(JSON.stringify({ type: "system.connected", timestamp: new Date().toISOString() }));
});

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
  if (url.pathname !== "/events") return socket.destroy();
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Alalqami Agent API listening on http://0.0.0.0:${port}`);
  console.log(`Provider: ${normalizedProviderName(process.env)}`);
});
