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

    /**
     * Incremented every time we stop (or replace) the chat stream.
     * Each stream observer captures the generation at creation time and
     * silently drops callbacks if the generation has since advanced.
     * This prevents duplicate messages when a stale stream fires after reconnection.
     */
    private val streamGeneration = AtomicInteger(0)

    // ─── CONNECTION ───────────────────────────────────

    fun connect() {
        if (channel != null && !channel!!.isShutdown) return

        channel = ManagedChannelBuilder
            .forAddress(Constants.GRPC_HOST, Constants.GRPC_PORT)
            .usePlaintext()
            .build()

        asyncStub = ShoutBoxServiceGrpc.newStub(channel)
    }

    fun disconnect() {
        // Invalidate the current stream generation so its callbacks are ignored
        // even if gRPC delivers them after the channel shuts down.
        streamGeneration.incrementAndGet()
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

    // ─── SEND MESSAGE ────────────────────────────────

    /**
     * Sentinel error message that signals the channel is offline/not-ready.
     * The ViewModel uses this to keep the message in PENDING state rather
     * than marking it FAILED — it will be retried on reconnect.
     */
    companion object {
        const val ERROR_OFFLINE = "__OFFLINE__"
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

        // If the channel is not READY (WiFi down, TRANSIENT_FAILURE, etc.) do NOT
        // send into a dead transport — gRPC would immediately call onError which
        // would mark the message FAILED. Instead, signal OFFLINE so the ViewModel
        // keeps the message in PENDING state and retries it on reconnect.
        if (!isConnected()) {
            onError(ERROR_OFFLINE)
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
        }) ?: onError(ERROR_OFFLINE) // asyncStub was null — treat as offline
    }

    // ─── CHAT STREAM ─────────────────────────────────

    /**
     * Opens a new chat stream. Bumps the generation counter first so any
     * callbacks still in-flight from the previous stream are ignored.
     *
     * Note: the previous stream is already dead at the transport level because
     * [disconnect] shuts down the channel before [connect] creates a new one.
     * The generation guard is a belt-and-suspenders safety net.
     */
    fun startChatStream(
        onMessageReceived: (ChatMessage) -> Unit,
        onStreamError: (String) -> Unit
    ) {
        val myGeneration = streamGeneration.incrementAndGet()

        try {
            asyncStub?.chatStream(object : StreamObserver<Shoutbox.ChatMessage> {

                override fun onNext(message: Shoutbox.ChatMessage?) {
                    if (streamGeneration.get() != myGeneration) return
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
                    if (streamGeneration.get() != myGeneration) return
                    onStreamError(t?.message ?: "Stream disconnected")
                }

                override fun onCompleted() {
                    if (streamGeneration.get() != myGeneration) return
                    onStreamError("Stream ended by server")
                }
            })
        } catch (e: Exception) {
            if (streamGeneration.get() == myGeneration) {
                onStreamError(e.message ?: "Error starting chat stream")
            }
        }
    }

    /** Invalidates the current stream's generation without touching the channel. */
    fun stopChatStream() {
        streamGeneration.incrementAndGet()
    }
}