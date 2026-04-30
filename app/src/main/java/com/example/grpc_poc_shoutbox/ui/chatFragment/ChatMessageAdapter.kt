package com.example.grpc_poc_shoutbox.ui.chatFragment

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.grpc_poc_shoutbox.databinding.ItemMessageBinding
import com.example.grpc_poc_shoutbox.dto.ChatMessage
import com.example.grpc_poc_shoutbox.dto.MessageStatus

class ChatMessageAdapter(
    private val messages: List<ChatMessage>,
    private val currentUsername: String
) : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage, position: Int) {
            setMessageContent(message)

            val isCurrentUser = message.username == currentUsername
            setMessageAlignment(isCurrentUser)
            setMessageColor(message, isCurrentUser)

            setStatusIndicator(message, isCurrentUser)

            binding.cvMessage.setOnClickListener(null)
            binding.cvMessage.isClickable = false
        }

        private fun setMessageContent(message: ChatMessage) {
            binding.tvUsername.text = message.username
            binding.tvContent.text = message.content
            binding.tvTime.text = message.getFormattedTime()
        }

        private fun setMessageAlignment(isCurrentUser: Boolean) {
            val lp = binding.cvMessage.layoutParams as LinearLayout.LayoutParams
            lp.gravity = if (isCurrentUser) Gravity.END else Gravity.START
            binding.cvMessage.layoutParams = lp
        }

        private fun setMessageColor(message: ChatMessage, isCurrentUser: Boolean) {
            binding.apply {
                when {
                    message.isSystemMessage -> {
                        cvMessage.setCardBackgroundColor(color(android.R.color.holo_green_dark))
                        tvUsername.text = "SYSTEM"
                        tvUsername.visibility = View.VISIBLE
                    }
                    isCurrentUser -> {
                        cvMessage.setCardBackgroundColor(color(android.R.color.holo_blue_light))
                        tvUsername.visibility = View.GONE
                    }
                    else -> {
                        cvMessage.setCardBackgroundColor(color(android.R.color.darker_gray))
                        tvUsername.visibility = View.VISIBLE
                    }
                }
            }
        }

        private fun setStatusIndicator(message: ChatMessage, isCurrentUser: Boolean) {
            binding.apply {
                if (!isCurrentUser || message.isSystemMessage) {
                    tvStatus.visibility = View.GONE
                    cvMessage.alpha = 1.0f
                    return
                }

                tvStatus.visibility = View.VISIBLE
                cvMessage.alpha = 1.0f

                when (message.status) {
                    MessageStatus.PENDING -> {
                        tvStatus.text = "Pending"
                        tvStatus.setTextColor(color(android.R.color.holo_orange_light))
                        cvMessage.alpha = 0.7f
                    }
                    MessageStatus.SENT -> {
                        tvStatus.text = "✓"
                        tvStatus.setTextColor(color(android.R.color.white))
                    }
                    MessageStatus.FAILED -> {
                        tvStatus.text = "Failed"
                        tvStatus.setTextColor(color(android.R.color.holo_red_light))
                        cvMessage.setCardBackgroundColor(color(android.R.color.holo_red_dark))
                    }
                }
            }
        }
        private fun color(resId: Int): Int {
            return itemView.context.getColor(resId)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position], position)
    }

    override fun getItemCount(): Int = messages.size
}