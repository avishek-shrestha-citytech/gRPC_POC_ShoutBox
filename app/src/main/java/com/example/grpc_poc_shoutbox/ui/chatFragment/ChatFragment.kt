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
import java.util.UUID
import com.example.grpc_poc_shoutbox.dto.SendMessageRequestDTO
import com.example.grpc_poc_shoutbox.remote.GrpcClient
import io.grpc.stub.StreamObserver
import com.shoutbox.proto.Shoutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : BaseFragment<FragmentChatBinding>() {

    private lateinit var grpcClient: GrpcClient
    private lateinit var messageAdapter: ChatMessageAdapter
    private var username: String? = null
    private var streamObserver: StreamObserver<Shoutbox.ChatMessage>? = null
    private val messages = mutableListOf<ChatMessage>()

    private fun messageKey(username: String, content: String): String {
        return "$username|$content"
    }

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentChatBinding {
        return FragmentChatBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        username = arguments?.getString("username")
        if (username.isNullOrEmpty()) {
            showToast("Username not provided")
            return
        }

        grpcClient = GrpcClient(requireContext())
        setupUI()
        connectToServer()
    }

    private fun setupUI() {
        messageAdapter = ChatMessageAdapter(messages, username ?: "")

        binding.apply {
            tvUsername.text = "Username: $username"

            rvMessages.apply {
                layoutManager = LinearLayoutManager(requireContext()).apply {
                    stackFromEnd = true
                }
                adapter = messageAdapter
            }

            btnSend.setOnClickListener {
                sendMessage()
            }

            etMessage.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun connectToServer() {
        showToast("Connecting to server...")

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                grpcClient.connect()
                delay(500)

                withContext(Dispatchers.Main) {
                    if (grpcClient.isConnected()) {
                        showToast("Connected!")
                        startChatStream()
                    } else {
                        showToast("Connection failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Connection error: ${e.message}")
                }
            }
        }
    }

    private fun startChatStream() {
        if (streamObserver != null) return

        streamObserver = grpcClient.startChatStream(
            username = username ?: "",
            onMessageReceived = { message ->
                lifecycleScope.launch(Dispatchers.Main) {
                    val pendingIndex = messages.indexOfFirst { it.status == MessageStatus.PENDING && it.username == message.username && it.content == message.content }
                    if (pendingIndex != -1) {
                        val existing = messages[pendingIndex]
                        messages[pendingIndex] = existing.copy(
                            status = MessageStatus.SENT,
                            timestamp = message.timestamp,
                            id = message.id
                        )
                        messageAdapter.notifyItemChanged(pendingIndex)
                        return@launch
                    }

                    val duplicate = messages.any { it.username == message.username && it.content == message.content && it.timestamp == message.timestamp }
                    if (duplicate) return@launch

                    messages.add(message)
                    messageAdapter.notifyItemInserted(messages.size - 1)
                    binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                }
            },
            onError = { error ->
                lifecycleScope.launch(Dispatchers.Main) {
                    showToast("Stream error: $error")
                }
            }
        )
    }

    private fun sendMessage() {
        val messageText = binding.etMessage.text.toString().trim()

        if (messageText.isEmpty()) {
            showToast("Message cannot be empty")
            return
        }

        if (messageText.length > 500) {
            showToast("Message is too long (max 500 characters)")
            return
        }

        val request = SendMessageRequestDTO(
            username = username ?: "Unknown",
            content = messageText
        )

        if (!request.isValid()) {
            showToast("Invalid message")
            return
        }

        binding.apply {
            btnSend.isEnabled = false
            tvSendingStatus.text = "Sending..."
        }

        val tempId = UUID.randomUUID().toString()
        val localUsername = username ?: "Unknown"
        val localMsg = ChatMessage(
            username = localUsername,
            content = messageText,
            timestamp = System.currentTimeMillis(),
            isSystemMessage = false,
            tempId = tempId,
            status = MessageStatus.PENDING
        )

        messages.add(localMsg)
        val insertedIndex = messages.size - 1
        messageAdapter.notifyItemInserted(insertedIndex)
        binding.rvMessages.smoothScrollToPosition(insertedIndex)

        grpcClient.sendMessage(
            request = request,
            onSuccess = { response ->
                lifecycleScope.launch(Dispatchers.Main) {
                    val idx = messages.indexOfFirst { it.tempId == tempId }
                    if (response.success) {
                        if (idx != null && idx >= 0 && idx < messages.size) {
                            val existing = messages[idx]
                            messages[idx] = existing.copy(
                                status = MessageStatus.SENT,
                                timestamp = response.timestamp
                            )
                            messageAdapter.notifyItemChanged(idx)
                        }

                        binding.etMessage.text?.clear()
                        hideKeyboard()
                        binding.tvSendingStatus.text = ""
                    } else {
                        if (idx >= 0 && idx < messages.size) {
                            val existing = messages[idx]
                            messages[idx] = existing.copy(status = MessageStatus.FAILED)
                            messageAdapter.notifyItemChanged(idx)
                        }
                        binding.tvSendingStatus.text = "Failed"
                        showToast("Failed: ${response.message}")
                    }
                    binding.btnSend.isEnabled = true
                }
            },
            onError = { error ->
                lifecycleScope.launch(Dispatchers.Main) {
                    val idx = messages.indexOfFirst { it.tempId == tempId }
                    if (idx >= 0 && idx < messages.size) {
                        val existing = messages[idx]
                        messages[idx] = existing.copy(status = MessageStatus.FAILED)
                        messageAdapter.notifyItemChanged(idx)
                    }
                    binding.tvSendingStatus.text = "Error"
                    showToast("Error: $error")
                    binding.btnSend.isEnabled = true
                }
            }
        )
    }

    override fun onDestroyView() {
        streamObserver?.onCompleted()
        grpcClient.disconnect()
        super.onDestroyView()
    }
}