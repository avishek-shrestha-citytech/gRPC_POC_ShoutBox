package com.example.grpc_poc_shoutbox.ui.chatFragment

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.grpc_poc_shoutbox.dto.ChatMessage
import com.example.grpc_poc_shoutbox.dto.MessageStatus
import com.example.grpc_poc_shoutbox.dto.SendMessageRequestDTO
import com.example.grpc_poc_shoutbox.remote.GrpcClient
import io.grpc.ConnectivityState
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.UUID
import java.util.concurrent.TimeUnit

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val grpcClient = GrpcClient(application.applicationContext)
    private val disposables = CompositeDisposable()
    private var connectionDisposable: Disposable? = null
    private var autoReconnectDisposable: Disposable? = null
    private var serverStatusDisposable: Disposable? = null
    private var isStreamActive = false
    var username: String = ""

    /** All _messages mutations are posted here — serializes concurrent gRPC thread callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _connectionBar = MutableLiveData<Pair<String, Boolean>?>()
    val connectionBar: LiveData<Pair<String, Boolean>?> = _connectionBar
    private val _serverStatus = MutableLiveData<ConnectivityState>()
    val serverStatus: LiveData<ConnectivityState> = _serverStatus
    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages
    private val _toastEvent = MutableLiveData<String?>()
    val toastEvent: LiveData<String?> = _toastEvent

    /** Notifies Fragment to update a specific item in the adapter */
    private val _itemChanged = MutableLiveData<Int>()
    val itemChanged: LiveData<Int> = _itemChanged

    /** Notifies Fragment that a new message was inserted */
    private val _itemInserted = MutableLiveData<Int>()
    val itemInserted: LiveData<Int> = _itemInserted

    /** Whether the send button should be enabled */
    private val _sendEnabled = MutableLiveData(true)
    val sendEnabled: LiveData<Boolean> = _sendEnabled

    /** Sending status text ("Sending..." or "") */
    private val _sendingStatus = MutableLiveData("")
    val sendingStatus: LiveData<String> = _sendingStatus

    /** Signal to clear the message input field */
    private val _clearInput = MutableLiveData<Boolean>()
    val clearInput: LiveData<Boolean> = _clearInput

    /**Global Fallback ho --exceptions that cannot be delivered to a subscriber anymore**/
    init {
        RxJavaPlugins.setErrorHandler { e ->
            val cause = if (e is io.reactivex.rxjava3.exceptions.UndeliverableException) e.cause else e
            if (cause is InterruptedException) return@setErrorHandler
            Thread.currentThread().uncaughtExceptionHandler
                ?.uncaughtException(Thread.currentThread(), cause ?: e)
        }
    }

    fun connectToServer() {
        connectionDisposable?.dispose()

        _connectionBar.postValue("Connecting..." to false)
        _serverStatus.postValue(ConnectivityState.CONNECTING)

        connectionDisposable = Observable.fromCallable {
            try {
                grpcClient.disconnect()
                Thread.sleep(200)
                grpcClient.connect()
                waitForConnection(timeoutMs = 3000)
            } catch (_: InterruptedException) {
                false
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ connected ->
                    if (connected) onConnectionSuccess()
                else onConnectionFailed()
            }, {
                onConnectionFailed()
            })

        disposables.add(connectionDisposable!!)
    }

    /** Manual retry — stop auto-reconnect and try now */
    fun manualReconnect() {
        stopAutoReconnect()
        connectToServer()
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

    private fun onConnectionSuccess() {
        _connectionBar.postValue(null) // hide bar
        stopAutoReconnect()

        // Start a fresh stream (previous stream is dead — channel was replaced by disconnect/connect)
        isStreamActive = false
        startChatStream()

        retryAllFailedMessages()
    }

    private fun onConnectionFailed() {
        _connectionBar.postValue("Connection failed. Tap to retry." to true)
        startAutoReconnect()
    }

    // ─── AUTO-RECONNECT (every 10 seconds) ───────────

    private fun startAutoReconnect() {
        if (autoReconnectDisposable?.isDisposed == false) return

        autoReconnectDisposable = Observable.interval(3, TimeUnit.SECONDS)
            .flatMapSingle {
                Observable.fromCallable {
                    try {
                        grpcClient.disconnect()
                        Thread.sleep(200)
                        grpcClient.connect()
                        waitForConnection(timeoutMs = 3000)
                    } catch (_: InterruptedException) {
                        false
                    }
                }
                    .subscribeOn(Schedulers.io())
                    .firstOrError()
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ connected ->
                if (connected) {
                    onConnectionSuccess()
                } else {
                    _connectionBar.postValue("Connection failed. Tap to retry." to true)
                }
            }, {
                _connectionBar.postValue("Connection failed. Tap to retry." to true)
            })

        disposables.add(autoReconnectDisposable!!)
    }

    private fun stopAutoReconnect() {
        autoReconnectDisposable?.dispose()
        autoReconnectDisposable = null
    }

    // ─── SERVER STATUS PING (every 5 seconds) ────────

    /** Starts the periodic server status check */
    fun startServerStatusPing() {
        serverStatusDisposable?.dispose()

        var wasConnected = false

        serverStatusDisposable = Observable.interval(0, 5, TimeUnit.SECONDS)
            .observeOn(Schedulers.io())
            .map { grpcClient.getConnectionState() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                _serverStatus.postValue(state)

                val isNowConnected = (state == ConnectivityState.READY)

                // Detect server drop while we were chatting
                if (wasConnected && !isNowConnected && !isStreamActive) {
                    if (autoReconnectDisposable?.isDisposed != false) {
                        _connectionBar.postValue("Server went down. Reconnecting..." to true)
                        startAutoReconnect()
                    }
                }

                wasConnected = isNowConnected
            }, {
                _serverStatus.postValue(ConnectivityState.SHUTDOWN)
            })

        disposables.add(serverStatusDisposable!!)
    }

    // ─── CHAT STREAM ─────────────────────────────────

    private fun startChatStream() {
        isStreamActive = true

        grpcClient.startChatStream(
            onMessageReceived = { message ->
                mainHandler.post { handleIncomingMessage(message) }
            },
            onStreamError = { _ ->
                mainHandler.post { handleStreamDisconnect() }
            }
        )
    }

    private fun handleStreamDisconnect() {
        isStreamActive = false
        _connectionBar.postValue("Disconnected. Reconnecting..." to false)
        startAutoReconnect()
    }

    private fun handleIncomingMessage(message: ChatMessage) {
        if (tryConfirmPendingMessage(message)) return
        if (isDuplicate(message)) return

        _messages.add(message)
        _itemInserted.postValue(_messages.size - 1)
    }

    private fun tryConfirmPendingMessage(message: ChatMessage): Boolean {
        val index = _messages.indexOfFirst {
            // Match PENDING or FAILED — a broadcast is ground truth that the server received it.
            // FAILED can occur if onError raced ahead of the stream delivery on reconnect.
            (it.status == MessageStatus.PENDING || it.status == MessageStatus.FAILED) &&
                    it.username == message.username &&
                    it.content == message.content
        }
        if (index == -1) return false

        _messages[index] = _messages[index].copy(
            status = MessageStatus.SENT,
            timestamp = message.timestamp
        )
        _itemChanged.postValue(index)
        return true
    }

    private fun isDuplicate(message: ChatMessage): Boolean {
        return _messages.any {
            it.username == message.username &&
                    it.content == message.content &&
                    it.timestamp == message.timestamp
        }
    }

    // ─── SEND MESSAGE ────────────────────────────────

    /** Validates and sends a message. Returns false if validation fails. */
    fun sendMessage(messageText: String) {
        val trimmed = messageText.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.length > 500) {
            _toastEvent.postValue("Message is too long (max 500 characters)")
            return
        }

        val request = SendMessageRequestDTO(
            username = username,
            content = trimmed
        )

        _sendEnabled.postValue(false)
        _sendingStatus.postValue("Sending...")

        // Add optimistic message
        val tempId = UUID.randomUUID().toString()
        val optimisticMessage = ChatMessage(
            username = username,
            content = trimmed,
            timestamp = System.currentTimeMillis(),
            isSystemMessage = false,
            tempId = tempId,
            status = MessageStatus.PENDING
        )
        _messages.add(optimisticMessage)
        _itemInserted.postValue(_messages.size - 1)

        // Clear the input immediately — the message bubble is already visible
        _clearInput.postValue(true)

        // Send via gRPC
        grpcClient.sendMessage(
            request = request,
            onSuccess = { response ->
                mainHandler.post { handleSendSuccess(response, tempId) }
            },
            onError = { error ->
                mainHandler.post { handleSendFailure(tempId, error) }
            }
        )
    }

    private fun handleSendSuccess(
        response: com.example.grpc_poc_shoutbox.dto.SendMessageResponseDTO,
        tempId: String
    ) {
        val idx = _messages.indexOfFirst { it.tempId == tempId }

        if (response.success) {
            if (idx >= 0 && idx < _messages.size) {
                _messages[idx] = _messages[idx].copy(
                    status = MessageStatus.SENT,
                    timestamp = response.timestamp
                )
                _itemChanged.postValue(idx)
            }
            _sendingStatus.postValue("")
        } else {
            markMessageFailed(idx)
        }

        _sendEnabled.postValue(true)
    }

    private fun handleSendFailure(tempId: String, error: String) {
        val idx = _messages.indexOfFirst { it.tempId == tempId }

        if (error == GrpcClient.ERROR_OFFLINE) {
            // Channel is not ready (WiFi down, etc.) — keep the message PENDING
            // so it will be retried automatically when the connection comes back.
            // Do NOT call markMessageFailed; the bubble already shows PENDING.
        } else {
            markMessageFailed(idx)
        }

        _sendingStatus.postValue("")
        _sendEnabled.postValue(true)
    }

    private fun markMessageFailed(index: Int) {
        if (index >= 0 && index < _messages.size) {
            // Never downgrade a message that was already confirmed as SENT
            // (e.g. stream delivered the broadcast before the unary RPC's onError fired)
            if (_messages[index].status == MessageStatus.SENT) return
            _messages[index] = _messages[index].copy(status = MessageStatus.FAILED)
            _itemChanged.postValue(index)
        }
    }

    // ─── RETRY FAILED MESSAGES ───────────────────────
    private fun retryAllFailedMessages() {
        // Retry both PENDING and FAILED messages.
        // Do NOT pre-convert PENDING → FAILED — that caused a race where:
        //   1. PENDING is marked FAILED and given a newTempId.
        //   2. The stream broadcast arrives and confirms it as SENT via tryConfirmPendingMessage.
        //   3. handleSendSuccess arrives for newTempId — the message is already SENT, which is fine.
        //   BUT if a gRPC onError fires for the new RPC after the stream confirmed SENT, the
        //   old PENDING→FAILED conversion left the door open for subtle ordering bugs.
        // Retrying PENDING directly is safe: retrySingleMessage gives it a fresh tempId so any
        // stale in-flight RPC callback (if it ever fires) won’t find the message by tempId.
        val toRetry = _messages.indices.filter {
            _messages[it].status == MessageStatus.PENDING ||
            _messages[it].status == MessageStatus.FAILED
        }
        if (toRetry.isEmpty()) return

        for (position in toRetry) {
            retrySingleMessage(position)
        }
    }

    /** Retry the message at [position] (must be PENDING or FAILED status). */
    private fun retrySingleMessage(position: Int) {
        if (position < 0 || position >= _messages.size) return

        val msg = _messages[position]
        // Accept both PENDING (offline-queued) and FAILED (server-rejected) messages.
        if (msg.status != MessageStatus.FAILED && msg.status != MessageStatus.PENDING) return

        // Assign a fresh tempId so any in-flight RPC for the old tempId can never
        // accidentally find and mutate this message again.
        val newTempId = UUID.randomUUID().toString()
        _messages[position] = msg.copy(
            status = MessageStatus.PENDING,
            tempId = newTempId
        )
        _itemChanged.postValue(position)

        val request = SendMessageRequestDTO(
            username = msg.username,
            content = msg.content
        )

        grpcClient.sendMessage(
            request = request,
            onSuccess = { response ->
                mainHandler.post {
                    // Mark by position + tempId guard.
                    // If the tempId at this position has since changed (another retry cycle
                    // ran and replaced it), this callback is stale — ignore it.
                    if (position < _messages.size &&
                        _messages[position].tempId == newTempId &&
                        _messages[position].status != MessageStatus.SENT
                    ) {
                        _messages[position] = _messages[position].copy(
                            status = MessageStatus.SENT,
                            timestamp = response.timestamp
                        )
                        _itemChanged.postValue(position)
                    }
                }
            },
            onError = { error ->
                mainHandler.post { handleSendFailure(newTempId, error) }
            }
        )
    }

    // ─── CLEANUP ─────────────────────────────────────

    override fun onCleared() {
        serverStatusDisposable?.dispose()
        disposables.clear()
        grpcClient.disconnect()
        isStreamActive = false
        super.onCleared()
    }
}
