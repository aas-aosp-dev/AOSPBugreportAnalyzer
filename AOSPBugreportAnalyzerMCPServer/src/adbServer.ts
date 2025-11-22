import { spawn } from "child_process";
import fs from "fs";
import path from "path";
import { Server } from "@modelcontextprotocol/sdk/server";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio";
import { z } from "zod";

interface CommandResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

async function runCommand(command: string, args: string[]): Promise<CommandResult> {
  return await new Promise((resolve) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });

    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.on("error", (error) => {
      stderr += `\n${error.message}`;
    });

    child.on("close", (code) => {
      resolve({ stdout, stderr, exitCode: code ?? -1 });
    });
  });
}

async function ensureDir(dir: string): Promise<void> {
  await fs.promises.mkdir(dir, { recursive: true });
}

async function fileExistsAndNotEmpty(filePath: string): Promise<boolean> {
  try {
    const stat = await fs.promises.stat(filePath);
    return stat.isFile() && stat.size > 0;
  } catch (error) {
    return false;
  }
}

function parseZipPathFromOutput(output: string): string | null {
  const match = output.match(/Bug report copied to\s+(.+\.zip)/i);
  return match?.[1]?.trim() ?? null;
}

async function collectBugreportZip(serial: string): Promise<{ zipPath: string; stdout: string; stderr: string; exitCode: number }>
{
  const bugreportsDir = path.join(process.cwd(), "bugreports");
  await ensureDir(bugreportsDir);

  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const zipFileName = `bugreport-${serial}-${timestamp}.zip`;
  const zipFilePath = path.join(bugreportsDir, zipFileName);

  console.log("[MCP-ADB] Running adb bugreport for serial:", serial, "->", zipFilePath);
  const { stdout, stderr, exitCode } = await runCommand("adb", ["-s", serial, "bugreport", zipFilePath]);

  let chosenPath = zipFilePath;
  if (!(await fileExistsAndNotEmpty(zipFilePath))) {
    const fallback = parseZipPathFromOutput(stdout) ?? parseZipPathFromOutput(stderr);
    if (fallback && (await fileExistsAndNotEmpty(fallback))) {
      console.log("[MCP-ADB] Bugreport zip missing at requested path, using fallback from adb output:", fallback);
      chosenPath = fallback;
    }
  }

  return { zipPath: chosenPath, stdout, stderr, exitCode };
}

async function handleGetBugreport(serial: string) {
  const { zipPath, stdout, stderr, exitCode } = await collectBugreportZip(serial);

  if (exitCode !== 0) {
    console.error("[MCP-ADB] adb bugreport failed:", { exitCode, stdout, stderr });
    return {
      isError: true,
      content: [
        {
          type: "text",
          text: `adb bugreport failed with code ${exitCode}`,
        },
      ],
    };
  }

  const stat = await fs.promises.stat(zipPath).catch(() => null);
  if (!stat || !stat.isFile() || stat.size === 0) {
    console.error("[MCP-ADB] Bugreport zip file not found or empty:", zipPath);
    return {
      isError: true,
      content: [
        {
          type: "text",
          text: `Bugreport zip file was not created: ${zipPath}`,
        },
      ],
    };
  }

  console.log("[MCP-ADB] Saved bugreport ZIP to:", zipPath);
  return {
    content: [
      {
        type: "text",
        text: `Bugreport saved to ${zipPath}`,
      },
    ],
    structuredContent: {
      filePath: zipPath,
    },
  };
}

async function handleListDevices() {
  const { stdout, stderr, exitCode } = await runCommand("adb", ["devices", "-l"]);
  if (exitCode !== 0) {
    console.error("[MCP-ADB] adb devices failed:", { exitCode, stdout, stderr });
    return {
      isError: true,
      content: [
        {
          type: "text",
          text: `adb devices failed with code ${exitCode}`,
        },
      ],
    };
  }

  return {
    content: [
      {
        type: "text",
        text: stdout.trim(),
      },
    ],
  };
}

const server = new Server(
  {
    name: "AOSP Bugreport Analyzer ADB MCP Server",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

server.tool(
  {
    name: "adb.list_devices",
    description: "List connected Android devices via adb",
    inputSchema: z.object({}),
  },
  async () => handleListDevices()
);

server.tool(
  {
    name: "adb.get_bugreport",
    description: "Collect an Android bugreport and return a path to the generated ZIP file",
    inputSchema: z.object({
      serial: z.string(),
    }),
    outputSchema: z.object({
      filePath: z.string(),
    }),
  },
  async ({ input }) => handleGetBugreport(input.serial)
);

const transport = new StdioServerTransport();
server.connect(transport).catch((error) => {
  console.error("[MCP-ADB] Failed to start server:", error);
  process.exit(1);
});
