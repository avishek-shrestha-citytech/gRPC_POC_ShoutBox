package com.example.grpc_poc_shoutbox.dto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val username: String = "",
    val content: String = "",
    val timestamp: Long = 0L,
    val isSystemMessage: Boolean = false,
    val id: String? = null,

    val tempId: String? = null,
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


