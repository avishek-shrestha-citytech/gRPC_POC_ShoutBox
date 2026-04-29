package com.example.grpc_poc_shoutbox.ui.chatFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.grpc_poc_shoutbox.baseClass.BaseFragment
import com.example.grpc_poc_shoutbox.databinding.FragmentChatBinding
import com.example.grpc_poc_shoutbox.dto.ChatMessage
import com.example.grpc_poc_shoutbox.dto.MessageStatus
import com.example.grpc_poc_shoutbox.dto.SendMessageRequestDTO
import com.example.grpc_poc_shoutbox.dto.SendMessageResponseDTO
import com.example.grpc_poc_shoutbox.remote.GrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ChatFragment — the main chat screen.
 *
 * Error states handled (from Concept.txt):
 *  1. Cannot connect     → "Connecting..." 3s → "Connection failed. Tap to retry."
 *  2. Message send fails → Keep text in input → Toast "Failed to send. Try again."
 *  3. Stream disconnects → "Reconnecting..." → Auto-reconnect every 10s
 *  4. Empty message      → SEND button does nothing
 *  5. Failed messages    → Tap to retry sending
 */
class ChatFragment : BaseFragment<FragmentChatBinding>() {

    // gRPC client instance
    private lateinit var grpcClient: GrpcClient

    // RecyclerView adapter
    private lateinit var messageAdapter: ChatMessageAdapter

    // Current user's name (passed from JoinFragment)
    private var username: String? = null

    // All messages displayed in the chat
    private val messages = mutableListOf<ChatMessage>()

    // The coroutine job for the current connection attempt
    private var connectionJob: Job? = null

    // The coroutine job for the auto-reconnect timer
    private var autoReconnectJob: Job? = null

    // Whether we're currently connected and streaming
    private var isStreamActive = false

    // ─── LIFECYCLE ────────────────────────────────────

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentChatBinding {
        return FragmentChatBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get the username from JoinFragment
        username = arguments?.getString("username")
        if (username.isNullOrEmpty()) {
            showToast("Username not provided")
            return
        }

        // Initialize
        grpcClient = GrpcClient(requireContext())
        setupRecyclerView()
        setupSendButton()
        setupConnectionStatusBar()
        connectToServer()
    }

    override fun onDestroyView() {
        // Stop everything
        connectionJob?.cancel()
        stopAutoReconnect()
        grpcClient.disconnect()
        isStreamActive = false
        super.onDestroyView()
    }

    // ─── UI SETUP ─────────────────────────────────────

    /** Sets up the RecyclerView with the message adapter */
    private fun setupRecyclerView() {
        // Pass the retry callback to the adapter
        messageAdapter = ChatMessageAdapter(
            messages = messages,
            currentUsername = username ?: "",
            onRetryClick = { position -> retryFailedMessage(position) }
        )

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // Start from bottom like a chat
            }
            adapter = messageAdapter
        }

        // Show the username in the header
        binding.tvUsername.text = "Username: $username"
    }

    /** Sets up the SEND button and keyboard action */
    private fun setupSendButton() {
        // Tap the SEND button
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        // Press "Send" on keyboard
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    /** Makes the connection status bar tappable for manual retry */
    private fun setupConnectionStatusBar() {
        binding.tvConnectionStatus.setOnClickListener {
            // Manual retry — stop auto-reconnect and try now
            stopAutoReconnect()
            connectToServer()
        }
    }

    // ─── CONNECTION ───────────────────────────────────
    //
    // Concept: "Connecting..." for 3 seconds → "Connection failed. Tap to retry."
    //

    /** Connects to the gRPC server with a 3-second timeout */
    private fun connectToServer() {
        // Cancel any previous attempt
        connectionJob?.cancel()

        // Show "Connecting..." bar
        showConnectionBar("Connecting...", isError = false)

        connectionJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Tear down old connection first (clean slate)
                grpcClient.disconnect()
                delay(200)

                // Open the channel
                grpcClient.connect()

                // Wait up to 3 seconds for connection
                val connected = waitForConnection(timeoutMs = 3000)

                withContext(Dispatchers.Main) {
                    if (connected) {
                        onConnectionSuccess()
                    } else {
                        onConnectionFailed()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onConnectionFailed()
                }
            }
        }
    }

    /** Polls isConnected() every 200ms up to the timeout */
    private suspend fun waitForConnection(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (grpcClient.isConnected()) return true
            delay(200)
        }
        return false
    }

    /** Called when connection succeeds */
    private fun onConnectionSuccess() {
        hideConnectionBar()
        showToast("Connected!")
        stopAutoReconnect() // No need to keep checking
        startChatStream()
        retryAllFailedMessages() // Send any messages queued while offline
    }

    /** Called when connection fails or times out */
    private fun onConnectionFailed() {
        showConnectionBar("Connection failed. Tap to retry.", isError = true)
        startAutoReconnect() // Start checking every 10s
    }

    // ─── AUTO-RECONNECT (every 10 seconds) ───────────

    /** Starts a background timer that tries to reconnect every 10 seconds */
    private fun startAutoReconnect() {
        // Don't start a second timer
        if (autoReconnectJob?.isActive == true) return

        autoReconnectJob = lifecycleScope.launch(Dispatchers.Default) {
            while (true) {
                delay(10_000) // Wait 10 seconds

                // Try connecting
                grpcClient.disconnect()
                delay(200)
                grpcClient.connect()

                // Check if it worked (3 second timeout)
                val connected = waitForConnection(timeoutMs = 3000)

                withContext(Dispatchers.Main) {
                    if (connected) {
                        // Connected! Stop auto-reconnect and set up stream
                        onConnectionSuccess()
                    } else {
                        // Still not connected — update the bar
                        showConnectionBar("Connection failed. Tap to retry.", isError = true)
                    }
                }

                // If connected, stop the loop
                if (connected) break
            }
        }
    }

    /** Stops the auto-reconnect timer */
    private fun stopAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    // ─── CONNECTION STATUS BAR ────────────────────────

    /** Shows the connection status bar with a message */
    private fun showConnectionBar(text: String, isError: Boolean) {
        binding.tvConnectionStatus.apply {
            visibility = View.VISIBLE
            this.text = text

            // Orange for "working on it", Red for "failed"
            val color = if (isError) {
                requireContext().getColor(android.R.color.holo_red_dark)
            } else {
                requireContext().getColor(android.R.color.holo_orange_dark)
            }
            setBackgroundColor(color)
        }
        // NOTE: send button stays enabled — users can queue messages while offline
    }

    /** Hides the connection status bar */
    private fun hideConnectionBar() {
        binding.tvConnectionStatus.visibility = View.GONE
    }

    // ─── CHAT STREAM ──────────────────────────────────

    /** Opens the bidirectional chat stream */
    private fun startChatStream() {
        isStreamActive = true

        grpcClient.startChatStream(
            onMessageReceived = { message ->
                // Got a message — handle on main thread
                lifecycleScope.launch(Dispatchers.Main) {
                    handleIncomingMessage(message)
                }
            },
            onStreamError = { error ->
                // Stream died — handle on main thread
                lifecycleScope.launch(Dispatchers.Main) {
                    handleStreamDisconnect()
                }
            }
        )
    }

    /** Called when the stream disconnects or errors out */
    private fun handleStreamDisconnect() {
        isStreamActive = false
        showToast("Disconnected. Reconnecting...")
        showConnectionBar("Disconnected. Reconnecting...", isError = false)

        // Start auto-reconnect timer (every 10s)
        startAutoReconnect()
    }

    // ─── INCOMING MESSAGES ────────────────────────────

    /** Handles a message received from the server stream */
    private fun handleIncomingMessage(message: ChatMessage) {
        // Check if this confirms a pending optimistic message
        if (tryConfirmPendingMessage(message)) return

        // Check for duplicates
        if (isDuplicate(message)) return

        // It's a new message — add it to the list
        addMessageToList(message)
    }

    /** If we have a PENDING message matching this one, mark it SENT. Returns true if matched. */
    private fun tryConfirmPendingMessage(message: ChatMessage): Boolean {
        val index = messages.indexOfFirst {
            it.status == MessageStatus.PENDING &&
                    it.username == message.username &&
                    it.content == message.content
        }

        if (index == -1) return false

        // Update the pending message to SENT
        messages[index] = messages[index].copy(
            status = MessageStatus.SENT,
            timestamp = message.timestamp
        )
        messageAdapter.notifyItemChanged(index)
        return true
    }

    /** Returns true if we already have this exact message */
    private fun isDuplicate(message: ChatMessage): Boolean {
        return messages.any {
            it.username == message.username &&
                    it.content == message.content &&
                    it.timestamp == message.timestamp
        }
    }

    /** Adds a message to the list and scrolls down */
    private fun addMessageToList(message: ChatMessage) {
        messages.add(message)
        messageAdapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    // ─── SEND MESSAGE ─────────────────────────────────
    //
    // Concept: Message send fails → Keep text in input → Toast "Failed to send. Try again."
    //

    /** Validates and sends a message */
    private fun sendMessage() {
        val messageText = binding.etMessage.text.toString().trim()

        // Don't send empty messages
        if (messageText.isEmpty()) return

        // Don't send messages over 500 chars
        if (messageText.length > 500) {
            showToast("Message is too long (max 500 characters)")
            return
        }

        // Build the request
        val request = SendMessageRequestDTO(
            username = username ?: "Unknown",
            content = messageText
        )

        // Disable send button while sending
        binding.btnSend.isEnabled = false
        binding.tvSendingStatus.text = "Sending..."

        // Add the message optimistically (shows immediately as PENDING)
        val tempId = UUID.randomUUID().toString()
        addOptimisticMessage(messageText, tempId)

        // Send via gRPC
        sendViaGrpc(request, tempId)
    }

    /** Adds a PENDING message to the list (optimistic rendering) */
    private fun addOptimisticMessage(content: String, tempId: String) {
        val message = ChatMessage(
            username = username ?: "Unknown",
            content = content,
            timestamp = System.currentTimeMillis(),
            isSystemMessage = false,
            tempId = tempId,
            status = MessageStatus.PENDING
        )
        addMessageToList(message)
    }

    /** Sends a message request via gRPC with success/error handling */
    private fun sendViaGrpc(request: SendMessageRequestDTO, tempId: String) {
        grpcClient.sendMessage(
            request = request,
            onSuccess = { response ->
                lifecycleScope.launch(Dispatchers.Main) {
                    handleSendSuccess(response, tempId)
                }
            },
            onError = { error ->
                lifecycleScope.launch(Dispatchers.Main) {
                    handleSendFailure(tempId)
                }
            }
        )
    }

    /** Called when SendMessage RPC succeeds */
    private fun handleSendSuccess(response: SendMessageResponseDTO, tempId: String) {
        val idx = messages.indexOfFirst { it.tempId == tempId }

        if (response.success) {
            // Mark the optimistic message as SENT
            if (idx >= 0 && idx < messages.size) {
                messages[idx] = messages[idx].copy(
                    status = MessageStatus.SENT,
                    timestamp = response.timestamp
                )
                messageAdapter.notifyItemChanged(idx)
            }

            // Clear the input field (message sent successfully!)
            binding.etMessage.text?.clear()
            hideKeyboard()
            binding.tvSendingStatus.text = ""
        } else {
            // Server rejected the message
            markMessageFailed(idx)
            showToast("Failed to send. Try again.")
            // NOTE: text stays in input field so user can retry
        }

        binding.btnSend.isEnabled = true
    }

    /** Called when SendMessage RPC errors out */
    private fun handleSendFailure(tempId: String) {
        val idx = messages.indexOfFirst { it.tempId == tempId }
        markMessageFailed(idx)

        binding.tvSendingStatus.text = ""
        binding.btnSend.isEnabled = true

        // Concept: keep text in input, show toast
        showToast("Failed to send. Try again.")
    }

    /** Marks a message as FAILED in the list */
    private fun markMessageFailed(index: Int) {
        if (index >= 0 && index < messages.size) {
            messages[index] = messages[index].copy(status = MessageStatus.FAILED)
            messageAdapter.notifyItemChanged(index)
        }
    }

    // ─── RETRY FAILED MESSAGES ────────────────────────

    /** Called when user taps a FAILED message to retry sending it */
    private fun retryFailedMessage(position: Int) {
        // Check bounds
        if (position < 0 || position >= messages.size) return

        val failedMessage = messages[position]

        // Only retry FAILED messages
        if (failedMessage.status != MessageStatus.FAILED) return

        // Mark it as PENDING again
        val newTempId = UUID.randomUUID().toString()
        messages[position] = failedMessage.copy(
            status = MessageStatus.PENDING,
            tempId = newTempId
        )
        messageAdapter.notifyItemChanged(position)

        // Build a request and re-send
        val request = SendMessageRequestDTO(
            username = failedMessage.username,
            content = failedMessage.content
        )

        showToast("Retrying...")
        sendViaGrpc(request, newTempId)
    }

    /** Auto-retry ALL failed messages (called when reconnected) */
    private fun retryAllFailedMessages() {
        // Find all failed message positions
        val failedPositions = messages.indices.filter {
            messages[it].status == MessageStatus.FAILED
        }

        // Nothing to retry
        if (failedPositions.isEmpty()) return

        showToast("Resending ${failedPositions.size} message(s)...")

        // Retry each one
        for (position in failedPositions) {
            retryFailedMessage(position)
        }
    }
}