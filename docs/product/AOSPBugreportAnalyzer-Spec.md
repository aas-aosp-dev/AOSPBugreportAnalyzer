пше# AOSPBugreportAnalyzer — Product Specification

## 1. Overview

**Purpose:**  
Создать кроссплатформенное приложение для автоматизированного анализа Android Bugreport с использованием ИИ-агентов.

**Platforms:**  
- Android (Compose)
- Desktop (Compose Multiplatform)
- Backend (Ktor)
- Shared core (KMP)

**Key Value:**  
Ускорение диагностики, повышение качества, снижение стоимости регресса.

---

## 2. Core Problem

Bugreport — сложный, нетипизированный документ.  
Цель — быстро найти:
- корень проблемы
- категорию бага
- объяснение
- рекомендации

---

## 3. Core Features

### 3.1 Bugreport Parsing
- dumpsys / dumpstate / logcat
- tombstones / ANR traces
- CPU / memory / battery / network stats
- device props & system info
- app list & services

### 3.2 AI-Pipeline

| Stage | Agent Role |
|---|---|
Classification | тип проблемы |
Root cause | анализ причин |
Summary | объяснение |
Recommendations | советы и ссылки |

### 3.3 Reports & History
- Local DB (SQLite via SQLDelight)
- История, статусы, фильтры
- Экспорт PDF / HTML / JSON *(future)*

---

## 4. Architecture

- Clean Architecture
- MVI / unidirectional data flow
- DI (Koin/alternative)
- Shared business logic (KMP)
- Compose UI

Future module layout:

```
/core/parser
/core/ai
/core/domain
/core/data
/core/ui
/app/android
/app/desktop
/backend
/docs
```

---

## 5. AI Agent System

### 5.1 Agent Config

| Field | Description |
|---|---|
name | display name |
provider | OpenRouter / GigaChat / Yandex |
model | LLM identifier |
system prompt | persona & logic |
temperature | 0–2 |
maxTokens / topP / seed | optional |
strictJson | bool |

### 5.2 Default Setup

| Setting | Value |
|---|---|
Default provider | OpenRouter |
Default model | mistralai/mixtral-8x7b-instruct |
API key | вводит пользователь |

---

## 6. LLM Telemetry

Collected per response:

- model & provider
- latency (ms)
- tokens (input/output/total)
- estimated cost
- temperature & seed
- timestamp
- sessionId

Display via `ⓘ Usage` button in chat.

Stored in memory → later SQLite.

---

## 7. AI Lab Mode (R&D)

LAB is isolated from main UX.

Modules:

| Module | Goal |
|---|---|
AI Team Simulation | виртуальная команда инженеров |
Prompt Lab | temperature / persona tuning |

Enable manually via UI.

---

## 8. UI Requirements

### Shared
- Chat
- Settings
- Agents manager
- Message usage popup
- Copy message / copy chat

### Desktop Extra
- Tabs for multiple reports
- Drag & Drop
- LLM analytics dashboard *(future)*

---

## 9. Non‑Functional Requirements

| Category | Requirement |
|---|---|
Performance | UI feedback ≤ 5s |
Security | Local key storage, HTTPS |
Stability | tolerant to corrupted logs |
Scalability | backend horizontal |
Logging | structured logs, crash reporter |

---

## 10. Roadmap

| Milestone | Deliverable |
|---|---|
M0 | Base chat + OpenRouter |
M1 | Parser + AI pipeline |
M2 | Agents UI |
M3 | Usage + cost tracking |
M4 | SQLite history |
M5 | LAB modules |
M6 | Backend queue + caching |

---

## 11. File Policy

This spec must remain stable.  
Changes follow: Proposal → Review → Changelog update.

---

## 12. Vision

> Быстрый, понятный, умный анализ Android-проблем.
> ИИ‑ассистент для инженеров.

---

## 13. Maintainers

| Role | Person |
|---|---|
Product | Артём |
Tech lead | ChatGPT/Codex |
Contributors | TBD |
