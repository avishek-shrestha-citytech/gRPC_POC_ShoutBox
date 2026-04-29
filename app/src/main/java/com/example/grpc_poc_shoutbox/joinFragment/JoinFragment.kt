package com.example.grpc_poc_shoutbox.joinFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.navigation.fragment.findNavController
import com.example.grpc_poc_shoutbox.R
import com.example.grpc_poc_shoutbox.baseClass.BaseFragment
import com.example.grpc_poc_shoutbox.databinding.FragmentJoinBinding

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

                // Navigate to ChatFragment
                val bundle = Bundle().apply {
                    putString("username", username)
                }
                findNavController().navigate(
                    R.id.action_joinFragment_to_chatFragment,
                    bundle
                )
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
}