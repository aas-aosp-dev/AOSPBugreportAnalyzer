package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.desktop.DesktopApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AOSP Bugreport Analyzer",
    ) {
        DesktopApp()
    }
}