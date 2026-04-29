package com.example.grpc_poc_shoutbox.dto

data class SendMessageResponseDTO(
    val success: Boolean = false,
    val timestamp: Long = 0L,
    val message: String? = null
)
