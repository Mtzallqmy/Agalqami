# Android app

Open this folder in Android Studio. The emulator reaches the host API at `10.0.2.2:8787`.

Current UI:
- Agent list
- Create demo agent
- Open agent
- Send task
- Receive `agent.message.delta` through WebSocket

For a physical device, change `API_BASE_URL` and `WS_BASE_URL` in `app/build.gradle.kts` to the development machine's LAN address or a deployed HTTPS endpoint.
