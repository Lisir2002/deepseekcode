package com.deepseek.coder.ser_test

import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.remote.dto.ChatMessageDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class SerTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun chatMessageDto_content_is_plain_string() {
        val req = ChatCompletionRequest(
            model = "deepseek-v4-flash",
            messages = listOf(
                ChatMessageDto(role = "system", content = "You are coding assistant."),
                ChatMessageDto(role = "user", content = "写 HelloWorld Kotlin")
            ),
            stream = false
        )
        val out = json.encodeToString(req)
        println("Serialized JSON: $out")
        // Requirement: content must be a plain JSON string, not an object like {"type":"Text","text":"..."}
        assertTrue("content should be plain string", "\"content\":\"You are coding assistant.\"" in out)
        assertTrue("content should be plain string 2", "\"content\":\"写 HelloWorld Kotlin\"" in out)
        assertFalse("Bug! Content serialized as object", "\"type\"" in out && "\"Text\"" in out)
    }
}
