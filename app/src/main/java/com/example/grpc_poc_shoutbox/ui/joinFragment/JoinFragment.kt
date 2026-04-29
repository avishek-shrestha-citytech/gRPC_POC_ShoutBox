package com.example.grpc_poc_shoutbox.ui.joinFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.example.grpc_poc_shoutbox.R
import com.example.grpc_poc_shoutbox.baseClass.BaseFragment
import com.example.grpc_poc_shoutbox.databinding.FragmentJoinBinding
import com.example.grpc_poc_shoutbox.ui.chatFragment.ChatFragment

class JoinFragment : BaseFragment<FragmentJoinBinding>() {

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentJoinBinding {
        return FragmentJoinBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
    }

    private fun setupUI() {
        binding.apply {
            btnJoinChat.setOnClickListener {
                val username = etUsername.text.toString().trim()

                if (username.isEmpty()) {
                    showToast("Please enter a username")
                    return@setOnClickListener
                }

                if (username.length > 20) {
                    showToast("Username must be 20 characters or less")
                    return@setOnClickListener
                }
                navigateToChat(username)
            }

            etUsername.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    btnJoinChat.performClick()
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Replace this fragment with ChatFragment, passing the username */
    private fun navigateToChat(username: String) {
        val chatFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putString("username", username)
            }
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, chatFragment)
            .addToBackStack(null)
            .commit()
    }
}