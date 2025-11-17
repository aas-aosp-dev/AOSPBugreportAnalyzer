# MCP Client Module

The `:mcpClient` module is a simple JVM console application that connects to an external
Model Context Protocol (MCP) server over stdio. The current implementation demonstrates
how to enumerate the tools exposed by a GitHub MCP server and, when available, invoke the
`github.get_pr_diff` tool to print a pull request diff.

## Requirements

1. A compatible MCP server implementation (for example, the GitHub MCP server from the
   official MCP reference repository).
2. Node.js or Python (depending on the chosen MCP server implementation).

## Configuration

Update the command inside `McpClientMain.kt` so it launches your local MCP server. For
example:

```kotlin
val config = McpServerConfig(
    command = listOf("node", "/abs/path/to/github-mcp-server.js")
)
```

The server command is currently hard-coded, but it can be refactored to use environment
variables or CLI arguments in the future.

## Running the Client

From the project root:

```bash
./gradlew :mcpClient:run
```

The client performs the following sequence:

1. Starts the configured MCP server process via `ProcessBuilder`.
2. Sends a `tools/list` request and prints the tool inventory.
3. If `github.get_pr_diff` is available, sends a `tools/call` request with the repository
   and pull-request details (currently hard-coded to `aas-aosp-dev/AOSPBugreportAnalyzer` PR
   #40) and prints the resulting diff payload.

Make sure your MCP server can authenticate with GitHub (usually through environment
variables or a config file) so the tool invocation succeeds.
