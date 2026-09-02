export type AgentStatus = "idle" | "running" | "waiting_approval" | "failed";

export interface AgentDefinition {
  id: string;
  name: string;
  instructions: string;
  model: string;
  status: AgentStatus;
  createdAt: string;
}

export interface CreateAgentInput {
  name: string;
  instructions: string;
  model?: string;
}

type EventBase = {
  eventId: string;
  runId: string;
  agentId: string;
  timestamp: string;
};

export type AgentEvent =
  | (EventBase & { type: "agent.started" })
  | (EventBase & { type: "agent.message.delta"; delta: string })
  | (EventBase & { type: "agent.message.completed"; text: string })
  | (EventBase & { type: "tool.started"; tool: string; callId: string })
  | (EventBase & { type: "tool.completed"; tool: string; callId: string; output?: unknown })
  | (EventBase & { type: "approval.required"; approvalId: string; title: string; detail: string })
  | (EventBase & { type: "agent.completed" })
  | (EventBase & { type: "agent.failed"; error: string });

export interface SendMessageInput {
  message: string;
}

export interface RunAccepted {
  runId: string;
}
