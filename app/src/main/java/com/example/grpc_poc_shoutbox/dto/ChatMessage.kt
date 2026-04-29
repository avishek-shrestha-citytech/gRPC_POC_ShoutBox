package com.example.grpc_poc_shoutbox.dto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val username: String = "",
    val content: String = "",
    val timestamp: Long = 0L,
    val isSystemMessage: Boolean = false,
    // Optional server-assigned ID (null when server doesn't provide one)
    val id: String? = null,
    // Local temporary ID used for optimistic rendering and swap
    val tempId: String? = null,
    // Local status for optimistic UI
    val status: MessageStatus = MessageStatus.SENT
) {
    fun isValid(): Boolean = username.isNotEmpty() && content.isNotEmpty()
    
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

enum class MessageStatus {
    PENDING,
    SENT,
    FAILED
}


