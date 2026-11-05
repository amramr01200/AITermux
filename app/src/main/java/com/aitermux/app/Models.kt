package com.aitermux.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

/** kind: "provider" | "termux" | "site" */
data class Provider(
    val id: String,
    val name: String,
    val url: String,
    val key: String,
    val kind: String,
    val email: String = "",
    val page: String = "",
    val modelsBase: String = ""
) {
    val apiBase: String get() = if (modelsBase.isNotBlank()) modelsBase else url
}

data class ModelEntry(
    val providerId: String,
    val modelId: String,
    val created: Long,
    val vision: Boolean,
    val fromTermux: Boolean
)

/** kind: "llm" (tool the model can call) | "program" (Termux program) */
data class ToolDef(
    val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val command: String,
    val installCmd: String,
    val url: String,
    val password: String
)

class Attachment(val name: String, val mime: String, val text: String?, val images: List<String>)

class Msg(val role: String, content: String, val attachments: List<Attachment> = emptyList()) {
    var content by mutableStateOf(content)
}

class Chat(val id: String, title: String, updated: Long) {
    var title by mutableStateOf(title)
    var updated by mutableStateOf(updated)
    val msgs = mutableStateListOf<Msg>()
}

class ConfirmReq(val text: String, val result: CompletableDeferred<Boolean>)
