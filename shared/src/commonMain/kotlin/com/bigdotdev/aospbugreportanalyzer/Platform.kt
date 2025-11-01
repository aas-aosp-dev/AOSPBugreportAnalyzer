package com.bigdotdev.aospbugreportanalyzer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform