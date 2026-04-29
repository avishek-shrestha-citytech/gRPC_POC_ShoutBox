package com.example.grpc_poc_shoutbox.remote

import android.content.Context
import com.example.grpc_poc_shoutbox.utils.Constants
import com.example.grpc_poc_shoutbox.dto.ChatMessage
import com.example.grpc_poc_shoutbox.dto.SendMessageRequestDTO
import com.example.grpc_poc_shoutbox.dto.SendMessageResponseDTO
import com.shoutbox.proto.ShoutBoxServiceGrpc
import com.shoutbox.proto.Shoutbox
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GrpcClient(private val context: Context) {
    private var channel: ManagedChannel? = null
    private var stub: ShoutBoxServiceGrpc.ShoutBoxServiceStub? = null
    private var blockingStub: ShoutBoxServiceGrpc.ShoutBoxServiceBlockingStub? = null

    fun connect() {
        if (channel != null && !channel!!.isShutdown) return

        channel = ManagedChannelBuilder
            .forAddress(Constants.GRPC_HOST, Constants.GRPC_PORT)
            .usePlaintext()
            .build()

        stub = ShoutBoxServiceGrpc.newStub(channel)
        blockingStub = ShoutBoxServiceGrpc.newBlockingStub(channel)
    }

    fun disconnect() {
        channel?.let {
            if (!it.isShutdown) {
                it.shutdown()
            }
        }
        channel = null
        stub = null
        blockingStub = null
    }

    fun sendMessage(
        request: SendMessageRequestDTO,
        onSuccess: (SendMessageResponseDTO) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (!request.isValid()) {
                    onError("Invalid message")
                    return@launch
                }

                val protoRequest = Shoutbox.SendMessageRequest.newBuilder()
                    .setUsername(request.username)
                    .setContent(request.content)
                    .build()

                stub?.sendMessage(protoRequest, object :
                    StreamObserver<Shoutbox.SendMessageResponse> {
                    override fun onNext(response: Shoutbox.SendMessageResponse?) {
                        response?.let {
                            val dto = SendMessageResponseDTO(
                                success = it.success,
                                timestamp = it.timestamp,
                                message = it.message
                            )
                            onSuccess(dto)
                        }
                    }

                    override fun onError(t: Throwable?) {
                        onError(t?.message ?: "Unknown error sending message")
                    }

                    override fun onCompleted() {
                        // Message sent successfully
                    }
                })
            } catch (e: Exception) {
                onError(e.message ?: "Error sending message")
            }
        }
    }

    fun startChatStream(
        username: String,
        onMessageReceived: (ChatMessage) -> Unit,
        onError: (String) -> Unit
    ): StreamObserver<Shoutbox.ChatMessage>? {
        return try {
            stub?.chatStream(object : StreamObserver<Shoutbox.ChatMessage> {
                override fun onNext(message: Shoutbox.ChatMessage?) {
                    message?.let {
                        val dto = ChatMessage(
                            username = it.username,
                            content = it.content,
                            timestamp = it.timestamp,
                            isSystemMessage = it.isSystemMessage
                        )
                        onMessageReceived(dto)
                    }
                }

                override fun onError(t: Throwable?) {
                    onError(t?.message ?: "Stream error")
                }

                override fun onCompleted() {
                    onError("Stream ended")
                }
            })
        } catch (e: Exception) {
            onError(e.message ?: "Error starting chat stream")
            null
        }
    }

    fun isConnected(): Boolean {
        return channel != null && !channel!!.isShutdown
    }
}