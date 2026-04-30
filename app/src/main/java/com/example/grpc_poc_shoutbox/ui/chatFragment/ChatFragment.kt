package com.example.grpc_poc_shoutbox.ui.chatFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.grpc_poc_shoutbox.baseClass.BaseFragment
import com.example.grpc_poc_shoutbox.databinding.FragmentChatBinding
import com.example.grpc_poc_shoutbox.dto.ChatMessage
import com.example.grpc_poc_shoutbox.dto.MessageStatus
import com.example.grpc_poc_shoutbox.dto.SendMessageRequestDTO
import com.example.grpc_poc_shoutbox.dto.SendMessageResponseDTO
import com.example.grpc_poc_shoutbox.remote.GrpcClient
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.grpc.ConnectivityState
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ChatFragment — the main chat screen.
 *
 * Error states handled (from Concept.txt):
 *    . Cannot connect     → "Connecting..." 3s → "Connection failed. Tap to retry."
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

    // RxJava disposable management
    private val disposables = CompositeDisposable()
    private var connectionDisposable: Disposable? = null
    private var autoReconnectDisposable: Disposable? = null
    private var serverStatusDisposable: Disposable? = null

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

        // Safety net: handle RxJava errors that have nowhere to go
        // (e.g. InterruptedException after dispose). Without this,
        // undeliverable exceptions crash the app.
        RxJavaPlugins.setErrorHandler { e ->
            // InterruptedException after dispose is expected — ignore it
            val cause = if (e is io.reactivex.rxjava3.exceptions.UndeliverableException) e.cause else e
            if (cause is InterruptedException) {
                // Thread was interrupted after subscriber disposed — harmless
                return@setErrorHandler
            }
            // For any other unexpected error, log but don't crash
            Thread.currentThread().uncaughtExceptionHandler
                ?.uncaughtException(Thread.currentThread(), cause ?: e)
        }

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
        startServerStatusPing()
    }

    override fun onDestroyView() {
        // Stop everything — clears all disposables at once
        serverStatusDisposable?.dispose()
        disposables.clear()
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
        connectionDisposable?.dispose()

        // Show "Connecting..." bar
        showConnectionBar("Connecting...", isError = false)

        connectionDisposable = Observable.fromCallable {
            try {
                // Tear down old connection first (clean slate)
                grpcClient.disconnect()
                Thread.sleep(200)

                // Open the channel
                grpcClient.connect()

                // Poll for connection up to 3 seconds
                waitForConnection(timeoutMs = 3000)
            } catch (_: InterruptedException) {
                // Subscription was disposed while we were sleeping — that's fine
                false
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ connected ->
                if (!isAdded) return@subscribe
                if (connected) {
                    onConnectionSuccess()
                } else {
                    onConnectionFailed()
                }
            }, {
                if (isAdded) onConnectionFailed()
            })

        disposables.add(connectionDisposable!!)
    }

    /** Polls isConnected() every 200ms up to the timeout (blocking — runs on IO thread) */
    @Throws(InterruptedException::class)
    private fun waitForConnection(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (Thread.interrupted()) throw InterruptedException()
            if (grpcClient.isConnected()) return true
            Thread.sleep(200)
        }
        return false
    }

    /** Called when connection succeeds */
    private fun onConnectionSuccess() {
        hideConnectionBar()
        showToast("Connected!")
        stopAutoReconnect() // No need to keep checking

        // Stop old stream before starting a new one to prevent duplicate messages
        isStreamActive = false
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
        if (autoReconnectDisposable?.isDisposed == false) return

        autoReconnectDisposable = Observable.interval(10, TimeUnit.SECONDS)
            .flatMapSingle {
                Observable.fromCallable {
                    try {
                        grpcClient.disconnect()
                        Thread.sleep(200)
                        grpcClient.connect()

                        // Check if it worked (3 second timeout)
                        waitForConnection(timeoutMs = 3000)
                    } catch (_: InterruptedException) {
                        // Disposed while sleeping — that's fine
                        false
                    }
                }
                    .subscribeOn(Schedulers.io())
                    .firstOrError()
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ connected ->
                if (!isAdded) return@subscribe
                if (connected) {
                    // Connected! Stop auto-reconnect and set up stream
                    onConnectionSuccess()
                } else {
                    // Still not connected — update the bar
                    showConnectionBar("Connection failed. Tap to retry.", isError = true)
                }
            }, {
                // Error during reconnect attempt
                if (isAdded) showConnectionBar("Connection failed. Tap to retry.", isError = true)
            })

        disposables.add(autoReconnectDisposable!!)
    }

    /** Stops the auto-reconnect timer */
    private fun stopAutoReconnect() {
        autoReconnectDisposable?.dispose()
        autoReconnectDisposable = null
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

    // ─── SERVER STATUS PING (every 5 seconds) ─────────

    /** Starts a periodic ping that checks gRPC channel state and updates the header */
    private fun startServerStatusPing() {
        serverStatusDisposable?.dispose()

        // Track whether we were previously connected so we can detect drops
        var wasConnected = false

        serverStatusDisposable = Observable.interval(0, 5, TimeUnit.SECONDS)
            .observeOn(Schedulers.io())
            .map { grpcClient.getConnectionState() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                if (!isAdded) return@subscribe
                updateServerStatusText(state)

                val isNowConnected = (state == ConnectivityState.READY)

                // Detect server going down while we were chatting
                if (wasConnected && !isNowConnected && !isStreamActive) {
                    // Server dropped — kick off reconnect if not already running
                    if (autoReconnectDisposable?.isDisposed != false) {
                        showConnectionBar("Server went down. Reconnecting...", isError = true)
                        startAutoReconnect()
                    }
                }

                wasConnected = isNowConnected
            }, {
                // Error reading state — show offline
                if (isAdded) updateServerStatusText(ConnectivityState.SHUTDOWN)
            })

        disposables.add(serverStatusDisposable!!)
    }

    /** Maps gRPC ConnectivityState to user-friendly status text */
    private fun updateServerStatusText(state: ConnectivityState) {
        val (text, color) = when (state) {
            ConnectivityState.READY -> "🟢 Online" to 0xFF4CAF50.toInt()      // green
            ConnectivityState.CONNECTING -> "🟡 Connecting" to 0xFFFFEB3B.toInt() // yellow
            ConnectivityState.IDLE -> "🟡 Idle" to 0xFFFFEB3B.toInt()            // yellow
            ConnectivityState.TRANSIENT_FAILURE -> "🔴 Offline" to 0xFFF44336.toInt() // red
            ConnectivityState.SHUTDOWN -> "🔴 Offline" to 0xFFF44336.toInt()     // red
        }

        binding.tvServerStatus.text = text
        binding.tvServerStatus.setTextColor(color)
    }

    // ─── CHAT STREAM ──────────────────────────────────

    /** Opens the bidirectional chat stream */
    private fun startChatStream() {
        isStreamActive = true

        grpcClient.startChatStream(
            onMessageReceived = { message ->
                // Got a message — handle on main thread
                activity?.runOnUiThread {
                    if (isAdded) handleIncomingMessage(message)
                }
            },
            onStreamError = { error ->
                // Stream died — handle on main thread
                activity?.runOnUiThread {
                    if (isAdded) handleStreamDisconnect()
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
                activity?.runOnUiThread {
                    if (isAdded) handleSendSuccess(response, tempId)
                }
            },
            onError = { error ->
                activity?.runOnUiThread {
                    if (isAdded) handleSendFailure(tempId)
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