# Architecture Overview

The project follows a Clean Architecture split:

- **core/parser** — adapters that transform raw bugreports into structured artifacts.
- **core/ai** — provider clients, agent catalogs, and pricing for LLM calls.
- **core/domain** — chat orchestration, validation, and business policies.
- **core/data** — in-memory stores exposing flows with replaceable persistence.
- **core/ui** — UI models/state consumed by Compose screens.
- **app/desktop** and **app/android** — platform shells that render the shared UI.

New providers or persistence layers should implement the interfaces exposed by the core modules and be registered through dependency injection in the app layer.
