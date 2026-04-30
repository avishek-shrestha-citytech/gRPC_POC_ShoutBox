package com.example.grpc_poc_shoutbox.ui.chatFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.grpc_poc_shoutbox.baseClass.BaseFragment
import com.example.grpc_poc_shoutbox.databinding.FragmentChatBinding
import io.grpc.ConnectivityState

class ChatFragment : BaseFragment<FragmentChatBinding>() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var messageAdapter: ChatMessageAdapter

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentChatBinding {
        return FragmentChatBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //Username bundle bata taneko, na aayeko check garne
        val username = arguments?.getString("username")
        if (username.isNullOrEmpty()) {
            showToast("Username not provided")
            return
        }

        // Viewmodel initialization
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        viewModel.username = username

        setupRecyclerView(username)
        setupSendButton()
        setupConnectionStatusBar()
        observeViewModel()

        // Kick off connection + status ping
        viewModel.connectToServer()
        viewModel.startServerStatusPing()
    }

    //RecyclerView setup, adapter initialize garne, username display garne
    private fun setupRecyclerView(username: String) {
        messageAdapter = ChatMessageAdapter(
            messages = viewModel.messages,
            currentUsername = username
        )

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        binding.tvUsername.text = "Username: $username"
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            viewModel.sendMessage(text)
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.etMessage.text.toString()
                viewModel.sendMessage(text)
                true
            } else {
                false
            }
        }
    }

    private fun setupConnectionStatusBar() {
        binding.tvConnectionStatus.setOnClickListener {
            viewModel.manualReconnect()
        }
    }

    private fun observeViewModel() {
        viewModel.connectionBar.observe(viewLifecycleOwner) { barState ->
            if (barState == null) {
                binding.tvConnectionStatus.visibility = View.GONE
            } else {
                val (text, isError) = barState
                binding.tvConnectionStatus.apply {
                    visibility = View.VISIBLE
                    this.text = text

                    val color = if (isError) {
                        requireContext().getColor(android.R.color.holo_red_dark)
                    } else {
                        requireContext().getColor(android.R.color.holo_orange_dark)
                    }
                    setBackgroundColor(color)
                }
            }
        }

        viewModel.serverStatus.observe(viewLifecycleOwner) { state ->
            val (text, color) = when (state) {
                ConnectivityState.READY -> "Online" to 0xFFFFFFFF.toInt()
                ConnectivityState.CONNECTING -> "Connecting" to 0xFFFFFFFF.toInt()
                ConnectivityState.IDLE -> "Idle" to 0xFFFFFFFF.toInt()
                ConnectivityState.TRANSIENT_FAILURE -> "Offline" to 0xFFFFFFFF.toInt()
                ConnectivityState.SHUTDOWN -> "Offline" to 0xFFFFFFFF.toInt()
                else -> "Offline" to 0xFFFFFFFF.toInt()
            }
            binding.tvServerStatus.text = text
            binding.tvServerStatus.setTextColor(color)
        }

        viewModel.toastEvent.observe(viewLifecycleOwner) { message ->
            message?.let { showToast(it) }
        }

        viewModel.itemChanged.observe(viewLifecycleOwner) { position ->
            messageAdapter.notifyItemChanged(position)
        }

        viewModel.itemInserted.observe(viewLifecycleOwner) { position ->
            messageAdapter.notifyItemInserted(position)
            binding.rvMessages.smoothScrollToPosition(position)
        }

        viewModel.clearInput.observe(viewLifecycleOwner) { shouldClear ->
            if (shouldClear) {
                binding.etMessage.text?.clear()
                hideKeyboard()
            }
        }
    }
}