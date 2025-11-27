package com.bigdotdev.aospbugreportanalyzer

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

data class AdbCommandResult(
    val exitCode: Int,
    val stderrPreview: String
)

object AdbHelper {
    private fun runCommand(vararg args: String, outputFile: File): AdbCommandResult {
        val process = ProcessBuilder(*args)
            .redirectOutput(outputFile)
            .start()

        val stderr = ByteArrayOutputStream()
        val stderrReader = thread(start = true) {
            process.errorStream.use { stream ->
                val buffer = ByteArray(1024)
                var totalRead = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    val limit = (1000 - totalRead).coerceAtLeast(0)
                    if (limit == 0) break
                    val toWrite = minOf(read, limit)
                    stderr.write(buffer, 0, toWrite)
                    totalRead += toWrite
                    if (totalRead >= 1000) break
                }
            }
        }

        val exitCode = process.waitFor()
        stderrReader.join(200)
        val stderrPreview = stderr.toString().lineSequence().take(20).joinToString("\n").trim()
        return AdbCommandResult(exitCode = exitCode, stderrPreview = stderrPreview)
    }

    fun runBugreport(serial: String, outputFile: File): AdbCommandResult {
        return runCommand("adb", "-s", serial, "bugreport", outputFile = outputFile)
    }

    fun runLogcatDump(serial: String, outputFile: File): AdbCommandResult {
        return runCommand("adb", "-s", serial, "logcat", "-d", outputFile = outputFile)
    }
}
