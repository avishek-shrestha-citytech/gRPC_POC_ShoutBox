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
import java.util.concurrent.atomic.AtomicInteger

class GrpcClient(private val context: Context) {

    private var channel: ManagedChannel? = null
    private var asyncStub: ShoutBoxServiceGrpc.ShoutBoxServiceStub? = null
    private val streamGeneration = AtomicInteger(0)

    companion object {
        const val ERROR_OFFLINE = "__OFFLINE__"
    }

    fun connect() {
        if (channel != null && !channel!!.isShutdown) return

        channel = ManagedChannelBuilder
            .forAddress(Constants.GRPC_HOST, Constants.GRPC_PORT)
            .usePlaintext()
            .build()

        asyncStub = ShoutBoxServiceGrpc.newStub(channel)
    }

    fun disconnect() {
        streamGeneration.incrementAndGet()
        try {
            channel?.let { ch ->
                if (!ch.isShutdown) {
                    ch.shutdown()
                    if (!ch.awaitTermination(1, TimeUnit.SECONDS)) {
                        ch.shutdownNow()
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
        return ch.getState(true) == ConnectivityState.READY
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

        val stub = asyncStub
        if (stub == null) {
            onError(ERROR_OFFLINE)
            return
        }

        val protoRequest = Shoutbox.SendMessageRequest.newBuilder()
            .setUsername(request.username)
            .setContent(request.content)
            .build()

        stub.sendMessage(protoRequest, object : StreamObserver<Shoutbox.SendMessageResponse> {
            override fun onNext(response: Shoutbox.SendMessageResponse?) {
                response?.let {
                    onSuccess(SendMessageResponseDTO(
                        success = it.success,
                        timestamp = it.timestamp,
                        message = it.message
                    ))
                }
            }
            override fun onError(t: Throwable?) {
                val status = io.grpc.Status.fromThrowable(t)
                if (status.code == io.grpc.Status.Code.UNAVAILABLE) {
                    onError(ERROR_OFFLINE)
                } else {
                    onError(t?.message ?: "Unknown error sending message")
                }
            }
            override fun onCompleted() {}
        })
    }

    fun startChatStream(
        clientId: String,
        onMessageReceived: (ChatMessage) -> Unit,
        onStreamError: (String) -> Unit
    ) {
        val myGeneration = streamGeneration.incrementAndGet()
        val stub = asyncStub ?: run {
            onStreamError("Not connected")
            return
        }

        try {
            val requestStream = stub.chatStream(object : StreamObserver<Shoutbox.ChatMessage> {
                override fun onNext(message: Shoutbox.ChatMessage?) {
                    if (streamGeneration.get() != myGeneration) return
                    message?.let {
                        onMessageReceived(ChatMessage(
                            username = it.username,
                            content = it.content,
                            timestamp = it.timestamp,
                            isSystemMessage = it.isSystemMessage
                        ))
                    }
                }

                override fun onError(t: Throwable?) {
                    if (streamGeneration.get() != myGeneration) return
                    onStreamError(t?.message ?: "Stream error")
                }

                override fun onCompleted() {
                    if (streamGeneration.get() != myGeneration) return
                    onStreamError("Stream closed by server")
                }
            })

            requestStream.onNext(
                Shoutbox.ChatMessage.newBuilder()
                    .setClientId(clientId)
                    .build()
            )

        } catch (e: Exception) {
            if (streamGeneration.get() == myGeneration) {
                onStreamError(e.message ?: "Error starting stream")
            }
        }
    }
}