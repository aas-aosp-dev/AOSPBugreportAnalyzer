package com.bigdotdev.aospbugreportanalyzer

import java.io.File

object AdbHelper {
    fun runBugreport(serial: String, outputFile: File): Int {
        val process = ProcessBuilder("adb", "-s", serial, "bugreport")
            .redirectOutput(outputFile)
            .redirectErrorStream(true)
            .start()
        return process.waitFor()
    }

    fun runLogcatDump(serial: String, outputFile: File): Int {
        val process = ProcessBuilder("adb", "-s", serial, "logcat", "-d")
            .redirectOutput(outputFile)
            .redirectErrorStream(true)
            .start()
        return process.waitFor()
    }
}
