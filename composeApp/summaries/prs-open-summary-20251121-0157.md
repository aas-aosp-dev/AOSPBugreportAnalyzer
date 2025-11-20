## PR #49: Add pipeline command for PR summaries via MCP

### Краткое описание изменений
- Добавлен `sealed class PipelineMode` для режимов: `AllOpenPrs` (все открытые PR, лимит 5) и `SinglePr` (конкретный PR).
- Новая suspend-функция `runPrSummaryPipeline`: 
  - Получает список открытых PR и их diff через `McpGithubClient`.
  - Обрезает diff до 6000 символов.
  - Формирует структурированный промпт для LLM с описанием задачи (технический summary в Markdown: изменения, риски, ревью-чеклист).
  - Выводит прогресс через `onMessage` callback (на русском).
- Интеграция в чат-приложение (Compose Desktop).

### Возможные риски
- Зависимость от MCP-сервиса: сбои в `withMcpGithubClient` (network, auth) приведут к ошибкам пайплайна.
- Обрезка diff (6000 chars) может искажать анализ больших изменений.
- LLM-генерация: неточности в summaries из-за промпта или лимитов токенов.
- IO в coroutines без полного error-handling (try-catch только на верхнем уровне).

### Что особенно стоит проверить при ревью
- Корректность промпта: точное соответствие требуемому формату Markdown (изменения/риски/ревью).
- Обработку edge-кейсов: PR не найден, пустой список, большой diff, MCP-ошибки.
- Лимиты: take(5) PR, diff-обрезка — протестировать на реальных данных.
- UI-интеграцию: вызов `onMessage`, suspend в Compose scope.
- Логи: println для debug, отсутствие утечек.

## PR #48: Add PR summary pipeline via MCP

### Краткое описание изменений
- Добавлен `sealed class PipelineMode` (аналогично #49).
- Функция `handlePipelineCommand`: парсит чат-команды `/pipeline prs` (все открытые, лимит 5) или `/pipeline pr <num>`.
- Suspend `runPrSummaryPipeline`: 
  - Загружает PR через MCP (diff только для single PR).
  - Обрезает diff до 4000 символов.
  - Строит context для LLM с PR-деталями (title, URL, state, diff).
- Запуск в `scope.launch` с `isSending` флагом и error-handling.

### Возможные риски
- Аналогично #49: MCP-зависимость, rate-limits GitHub, LLM-неточности.
- Diff загружается только для single PR — для "all" summaries без diff (потеря деталей).
- Меньший лимит diff (4000 chars) vs #49.
- `addSystemMessage` для ошибок/UI — может спамить чат при частых вызовах.

### Что особенно стоит проверить при ревью
- Парсинг команд: regex split, toIntOrNull, ignoreCase.
- Diff-loading: `runCatching` + `getOrNull` — обработка failures без краша.
- Context-билдер: полнота данных PR, обрезка diff.
- Сравнение с #49: дублирование кода? #48 кажется базовой версией (#49 улучшает с callback и full-diff).
- Тестирование: команды в чате, MCP-моки, пустые/закрытые PR.