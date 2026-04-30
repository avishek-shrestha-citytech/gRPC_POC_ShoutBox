package com.example.grpc_poc_shoutbox.remote

import android.content.Context
import com.example.grpc_poc_shoutbox.utils.Constants
import com.example.grpc_poc_shoutbox.dto.ChatMessage
import com.example.grpc_poc_shoutbox.dto.SendMessageRequestDTO
import com.example.grpc_poc_shoutbox.dto.SendMessageResponseDTO
import com.shoutbox.proto.ShoutBoxServiceGrpc
import com.shoutbox.proto.Shoutbox
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import java.util.concurrent.TimeUnit

class GrpcClient(private val context: Context) {

    private var channel: ManagedChannel? = null
    private var asyncStub: ShoutBoxServiceGrpc.ShoutBoxServiceStub? = null

    fun connect() {
        if (channel != null && !channel!!.isShutdown) return

        channel = ManagedChannelBuilder
            .forAddress(Constants.GRPC_HOST, Constants.GRPC_PORT)
            .usePlaintext()
            .build()

        asyncStub = ShoutBoxServiceGrpc.newStub(channel)
    }

    fun disconnect() {
        try {
            channel?.let {
                if (!it.isShutdown) {
                    it.shutdown()
                    if (!it.awaitTermination(1, TimeUnit.SECONDS)) {
                        it.shutdownNow()
                    }
                }
            }
        } catch (_: Exception) {}
        channel = null
        asyncStub = null
    }
    fun isConnected(): Boolean {
        val ch = channel ?: return false
        if (ch.isShutdown || ch.isTerminated) return false
        val state = ch.getState(true)
        return state == ConnectivityState.READY
    }

    fun getConnectionState(): ConnectivityState {
        val ch = channel ?: return ConnectivityState.SHUTDOWN
        if (ch.isShutdown || ch.isTerminated) return ConnectivityState.SHUTDOWN
        return ch.getState(false)
    }

    fun sendMessage(
        request: SendMessageRequestDTO,
        onSuccess: (SendMessageResponseDTO) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!request.isValid()) {
            onError("Invalid message")
            return
        }

        val protoRequest = Shoutbox.SendMessageRequest.newBuilder()
            .setUsername(request.username)
            .setContent(request.content)
            .build()

        asyncStub?.sendMessage(protoRequest, object : StreamObserver<Shoutbox.SendMessageResponse> {

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
            override fun onCompleted() {}
        })
    }

    fun startChatStream(
        onMessageReceived: (ChatMessage) -> Unit,
        onStreamError: (String) -> Unit
    ): StreamObserver<Shoutbox.ChatMessage>? {
        return try {
            asyncStub?.chatStream(object : StreamObserver<Shoutbox.ChatMessage> {

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
                    onStreamError(t?.message ?: "Stream disconnected")
                }

                override fun onCompleted() {
                    onStreamError("Stream ended by server")
                }
            })
        } catch (e: Exception) {
            onStreamError(e.message ?: "Error starting chat stream")
            null
        }
    }
}