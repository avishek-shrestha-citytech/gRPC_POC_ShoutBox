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

/**
 * GrpcClient — handles connection, sending messages, and streaming.
 * Keeps it simple: connect, disconnect, send, stream.
 */
class GrpcClient(private val context: Context) {

    // The gRPC channel (like a socket connection)
    private var channel: ManagedChannel? = null

    // Async stub for non-blocking calls
    private var asyncStub: ShoutBoxServiceGrpc.ShoutBoxServiceStub? = null

    // ─── CONNECTION ───────────────────────────────────

    /**
     * Opens a gRPC channel to the server.
     * Does NOT block — the channel connects lazily on first RPC.
     */
    fun connect() {
        // Don't reconnect if already connected
        if (channel != null && !channel!!.isShutdown) return

        // Build the channel
        channel = ManagedChannelBuilder
            .forAddress(Constants.GRPC_HOST, Constants.GRPC_PORT)
            .usePlaintext() // No TLS for POC
            .build()

        // Create the async stub
        asyncStub = ShoutBoxServiceGrpc.newStub(channel)
    }

    /**
     * Shuts down the channel and clears stubs.
     * Safe to call multiple times.
     */
    fun disconnect() {
        try {
            channel?.let {
                if (!it.isShutdown) {
                    it.shutdown()
                    // Wait 1 second, then force-kill if still alive
                    if (!it.awaitTermination(1, TimeUnit.SECONDS)) {
                        it.shutdownNow()
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore — we're shutting down anyway
        }

        // Clear everything
        channel = null
        asyncStub = null
    }

    /**
     * Checks if the channel is actually READY (truly connected to server).
     * getState(true) forces the channel to start connecting if it's IDLE.
     */
    fun isConnected(): Boolean {
        val ch = channel ?: return false
        if (ch.isShutdown || ch.isTerminated) return false

        // getState(true) = "request connection if idle"
        val state = ch.getState(true)
        return state == ConnectivityState.READY
    }

    // ─── SEND MESSAGE (UNARY RPC) ─────────────────────

    /**
     * Sends a single message via Unary RPC.
     *
     * @param request   the message to send
     * @param onSuccess called with the server response on success
     * @param onError   called with error message on failure
     */
    fun sendMessage(
        request: SendMessageRequestDTO,
        onSuccess: (SendMessageResponseDTO) -> Unit,
        onError: (String) -> Unit
    ) {
        // Validate locally first
        if (!request.isValid()) {
            onError("Invalid message")
            return
        }

        // Build the proto request
        val protoRequest = Shoutbox.SendMessageRequest.newBuilder()
            .setUsername(request.username)
            .setContent(request.content)
            .build()

        // Make the async call
        asyncStub?.sendMessage(protoRequest, object : StreamObserver<Shoutbox.SendMessageResponse> {

            // Server sent back a response
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

            // Something went wrong
            override fun onError(t: Throwable?) {
                onError(t?.message ?: "Unknown error sending message")
            }

            // RPC completed (nothing to do here)
            override fun onCompleted() {}
        })
    }

    // ─── CHAT STREAM (BIDIRECTIONAL RPC) ──────────────

    /**
     * Opens a bidirectional chat stream.
     *
     * @param onMessageReceived  called each time the server sends a ChatMessage
     * @param onStreamError      called when the stream disconnects or errors
     * @return StreamObserver to send messages through, or null if failed
     */
    fun startChatStream(
        onMessageReceived: (ChatMessage) -> Unit,
        onStreamError: (String) -> Unit
    ): StreamObserver<Shoutbox.ChatMessage>? {
        return try {
            asyncStub?.chatStream(object : StreamObserver<Shoutbox.ChatMessage> {

                // Received a message from the server
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

                // Stream broke — server crashed, network died, etc.
                override fun onError(t: Throwable?) {
                    onStreamError(t?.message ?: "Stream disconnected")
                }

                // Server cleanly ended the stream
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