package com.adb.kitty.compose.data

import androidx.annotation.Keep

@Keep
data class CommandOption(
    val flag: String,
    val description: String
)

@Keep
data class CommandHelp(
    val title: String,
    val command: String,
    val description: String,
    val options: List<CommandOption> = emptyList()
)
