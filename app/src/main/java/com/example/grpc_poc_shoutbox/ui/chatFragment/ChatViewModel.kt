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

    var username: String = ""

    /** All _messages mutations MUST happen on the main thread via mainHandler.post{} */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionBar = MutableLiveData<Pair<String, Boolean>?>()
    val connectionBar: LiveData<Pair<String, Boolean>?> = _connectionBar

    private val _serverStatus = MutableLiveData<ConnectivityState>()
    val serverStatus: LiveData<ConnectivityState> = _serverStatus

    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages

    private val _toastEvent = MutableLiveData<String?>()
    val toastEvent: LiveData<String?> = _toastEvent

    private val _itemChanged = MutableLiveData<Int>()
    val itemChanged: LiveData<Int> = _itemChanged

    private val _itemInserted = MutableLiveData<Int>()
    val itemInserted: LiveData<Int> = _itemInserted

    private val _clearInput = MutableLiveData<Boolean>()
    val clearInput: LiveData<Boolean> = _clearInput

    init {
        RxJavaPlugins.setErrorHandler { e ->
            val cause = if (e is io.reactivex.rxjava3.exceptions.UndeliverableException) e.cause else e
            if (cause is InterruptedException) return@setErrorHandler
            Thread.currentThread().uncaughtExceptionHandler
                ?.uncaughtException(Thread.currentThread(), cause ?: e)
        }
    }

    // ─── CONNECTION ───────────────────────────────────────────────────────────

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
            } catch (_: InterruptedException) { false }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { connected -> if (connected) onConnectionSuccess() else onConnectionFailed() },
                { onConnectionFailed() }
            )

        disposables.add(connectionDisposable!!)
    }

    fun manualReconnect() {
        stopAutoReconnect()
        connectToServer()
    }

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
        _connectionBar.postValue(null)
        _serverStatus.postValue(ConnectivityState.READY)
        stopAutoReconnect()
        startChatStream()
        retryPendingMessages()
    }

    private fun onConnectionFailed() {
        _connectionBar.postValue("Connection failed. Tap to retry." to true)
        _serverStatus.postValue(ConnectivityState.TRANSIENT_FAILURE)
        startAutoReconnect()
    }

    // ─── AUTO-RECONNECT ───────────────────────────────────────────────────────

    private fun startAutoReconnect() {
        if (autoReconnectDisposable?.isDisposed == false) return

        autoReconnectDisposable = Observable.interval(4, TimeUnit.SECONDS)
            .flatMapSingle {
                Observable.fromCallable {
                    try {
                        grpcClient.disconnect()
                        Thread.sleep(200)
                        grpcClient.connect()
                        waitForConnection(timeoutMs = 3000)
                    } catch (_: InterruptedException) { false }
                }
                    .subscribeOn(Schedulers.io())
                    .firstOrError()
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { connected ->
                    if (connected) onConnectionSuccess()
                    else _connectionBar.postValue("Connection failed. Tap to retry." to true)
                },
                { _connectionBar.postValue("Connection failed. Tap to retry." to true) }
            )

        disposables.add(autoReconnectDisposable!!)
    }

    private fun stopAutoReconnect() {
        autoReconnectDisposable?.dispose()
        autoReconnectDisposable = null
    }

    // ─── SERVER STATUS PING ───────────────────────────────────────────────────

    fun startServerStatusPing() {
        serverStatusDisposable?.dispose()

        serverStatusDisposable = Observable.interval(0, 5, TimeUnit.SECONDS)
            .observeOn(Schedulers.io())
            .map { grpcClient.getConnectionState() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { state -> _serverStatus.postValue(state) },
                { _serverStatus.postValue(ConnectivityState.SHUTDOWN) }
            )

        disposables.add(serverStatusDisposable!!)
    }

    // ─── CHAT STREAM ──────────────────────────────────────────────────────────

    private val clientId = UUID.randomUUID().toString()

    private fun startChatStream() {
        grpcClient.startChatStream(
            clientId = clientId,
            onMessageReceived = { message ->
                mainHandler.post { handleIncomingMessage(message) }
            },
            onStreamError = { _ ->
                mainHandler.post { handleStreamDisconnect() }
            }
        )
    }

    private fun handleStreamDisconnect() {
        _connectionBar.postValue("Disconnected. Reconnecting..." to false)
        startAutoReconnect()
    }

    /**
     * Called for every message the stream pushes down (including our own echoed messages).
     *
     * Priority:
     * 1. If a PENDING/FAILED local message matches this echo by username+content → confirm it SENT.
     * 2. If we already have an identical SENT message (same username+content+timestamp) → skip.
     * 3. Otherwise → add as a new incoming message.
     */
    private fun handleIncomingMessage(message: ChatMessage) {
        // Try to confirm a pending message that we sent
        if (tryConfirmPendingMessage(message)) return
        // Skip exact duplicates (same timestamp = already confirmed by unary response)
        if (isDuplicate(message)) return

        _messages.add(message)
        _itemInserted.postValue(_messages.size - 1)
    }

    /**
     * Looks for the oldest PENDING or FAILED message from the same user with the same content
     * and marks it SENT with the server timestamp.
     *
     * We match oldest-first so that sending the same text twice in a row resolves in order.
     */
    private fun tryConfirmPendingMessage(message: ChatMessage): Boolean {
        val index = _messages.indexOfFirst { msg ->
            (msg.status == MessageStatus.PENDING || msg.status == MessageStatus.FAILED) &&
                    msg.username == message.username &&
                    msg.content == message.content
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
        return _messages.any { msg ->
            msg.username == message.username &&
                    msg.content == message.content &&
                    msg.timestamp == message.timestamp
        }
    }

    // ─── SEND MESSAGE ─────────────────────────────────────────────────────────

    fun sendMessage(messageText: String) {
        val trimmed = messageText.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.length > 500) {
            _toastEvent.postValue("Message is too long (max 500 characters)")
            return
        }

        val tempId = UUID.randomUUID().toString()

        // Add as PENDING immediately so the user sees it in the list
        val pendingMessage = ChatMessage(
            username = username,
            content = trimmed,
            timestamp = System.currentTimeMillis(),
            isSystemMessage = false,
            tempId = tempId,
            status = MessageStatus.PENDING
        )
        _messages.add(pendingMessage)
        _itemInserted.postValue(_messages.size - 1)
        _clearInput.postValue(true)

        doSend(tempId, username, trimmed)
    }

    /**
     * Performs the actual unary RPC send for a given tempId.
     * If offline → keep PENDING (will be retried on reconnect).
     * If RPC fails for any other reason → mark FAILED (also retried on reconnect).
     * On success → mark SENT with server timestamp.
     *
     * Note: the bidirectional stream will also echo this message back.
     * [tryConfirmPendingMessage] would match it, but by then the message is already
     * SENT, so the echo is treated as a duplicate and dropped. No double-add.
     */
    private fun doSend(tempId: String, user: String, content: String) {
        val request = SendMessageRequestDTO(username = user, content = content)

        grpcClient.sendMessage(
            request = request,
            onSuccess = { response ->
                mainHandler.post {
                    val idx = _messages.indexOfFirst { it.tempId == tempId }
                    if (idx < 0) return@post
                    // Only update if still pending (stream echo might have beaten us)
                    if (_messages[idx].status != MessageStatus.SENT) {
                        _messages[idx] = _messages[idx].copy(
                            status = MessageStatus.SENT,
                            timestamp = response.timestamp
                        )
                        _itemChanged.postValue(idx)
                    }
                }
            },
            onError = { error ->
                mainHandler.post {
                    val idx = _messages.indexOfFirst { it.tempId == tempId }
                    if (idx < 0) return@post
                    // Don't downgrade an already-SENT message
                    if (_messages[idx].status == MessageStatus.SENT) return@post

                    val newStatus = if (error == GrpcClient.ERROR_OFFLINE) {
                        MessageStatus.PENDING   // will retry on reconnect
                    } else {
                        MessageStatus.FAILED    // network/server error — also retried on reconnect
                    }
                    _messages[idx] = _messages[idx].copy(status = newStatus)
                    _itemChanged.postValue(idx)
                }
            }
        )
    }

    // ─── RETRY ────────────────────────────────────────────────────────────────

    /** Called after a successful reconnection — retries all PENDING and FAILED messages in order. */
    private fun retryPendingMessages() {
        val toRetry = _messages.indices.filter {
            _messages[it].status == MessageStatus.PENDING ||
                    _messages[it].status == MessageStatus.FAILED
        }
        for (pos in toRetry) {
            val msg = _messages[pos]
            // Assign a fresh tempId so the success/error callback can find the right row
            val newTempId = UUID.randomUUID().toString()
            _messages[pos] = msg.copy(tempId = newTempId, status = MessageStatus.PENDING)
            _itemChanged.postValue(pos)
            doSend(newTempId, msg.username, msg.content)
        }
    }

    // ─── CLEANUP ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        serverStatusDisposable?.dispose()
        disposables.clear()
        grpcClient.disconnect()
        super.onCleared()
    }
}
