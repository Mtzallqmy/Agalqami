# Alalqami Agent Architecture v0.1

## Goal

First vertical slice: **Android → API → Agent Runtime → Model Provider → WebSocket events → Android**.

## Boundaries

- Android is the control plane UI, not the long-running execution environment.
- Agent Runtime owns model turns and later tool loops.
- Computer/browser/sandbox execution will be a separate service in phase 2.
- All client-visible activity crosses a typed event protocol.

## Event flow

```text
Android POST /agents/{id}/messages
  -> API accepts run
  -> AgentRuntime emits agent.started
  -> Model provider streams text
  -> AgentRuntime emits agent.message.delta
  -> AgentRuntime emits agent.message.completed
  -> AgentRuntime emits agent.completed
  -> API broadcasts events on WS /events
  -> Android updates Compose state
```

## Next boundaries

1. Durable PostgreSQL repository instead of memory.
2. Authentication and per-user isolation.
3. Tool registry + policy engine.
4. Docker/VM computer service.
5. MCP connectors.
6. Human approvals.
7. Automations and subagents.
