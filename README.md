# AOSP Bugreport Analyzer

## Overview

This project contains a Compose Multiplatform desktop client and a Ktor-based backend server that together provide a chat-style interface for analysing Android bugreports. The current architecture follows clean boundaries between UI, application orchestration, and infrastructure layers. The desktop client talks **only** to the bundled backend (BFF) which in turn manages access to external LLM providers such as OpenRouter, OpenAI, or Groq.

## Getting started

### 1. Run the server (BFF)

```bash
./gradlew :server:run
```

The server starts on port `8080` by default. Configure provider credentials via environment variables before launching (for example, `OPENROUTER_API_KEY`).

### 2. Run the desktop client

```bash
./gradlew :composeApp:run
```

The desktop app connects to `http://localhost:8080` and proxies all chat requests through the BFF. Provider API keys are **never** entered in the UI.

## HTTP API quickstart

Send a synchronous completion request:

```bash
curl -X POST http://localhost:8080/api/v1/chat/complete \
  -H 'Content-Type: application/json' \
  -d '{
    "provider": "openrouter",
    "model": "gpt-4o-mini",
    "history": [{"role":"user","content":"Привет"}],
    "user_input": "Помоги проанализировать лог", 
    "strict_json": true,
    "system_prompt": null,
    "response_format": "json"
  }'
```

Example response:

```json
{
  "ok": true,
  "content_type": "json",
  "data": {
    "version": "1.0",
    "ok": true,
    "generated_at": "2025-01-01T12:00:00Z",
    "items": [],
    "error": ""
  },
  "text": null,
  "error": null
}
```

## Project structure

```
server/
  api/        # HTTP and future streaming endpoints
  app/        # Use cases (SendChat, StreamChat, etc.)
  domain/     # Provider-agnostic models
  infra/      # Provider clients, conversation store, configuration
composeApp/
  ...
  ApiClient.kt          # Lightweight HTTP client for the BFF
  DesktopChatMain.kt    # Compose UI + orchestration
```

The server already includes stubs for streaming, multi-agent orchestration, and conversation persistence to simplify future extensions.
