package com.example.grpc_poc_shoutbox.dto

data class SendMessageRequestDTO(
    val username: String = "",
    val content: String = ""
) {
    fun isValid(): Boolean = username.isNotEmpty() && content.isNotEmpty() && content.length <= 500
}
