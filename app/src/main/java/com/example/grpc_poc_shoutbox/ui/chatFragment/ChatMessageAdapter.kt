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

/**
 * ChatMessageAdapter — displays chat messages in the RecyclerView.
 *
 * Visual rules (from Concept.txt):
 *  - Other people's messages: left aligned, show username
 *  - Your messages: right aligned, no username
 *  - System messages: green background, show "SYSTEM"
 *  - Show sending status on your own messages (PENDING / SENT / FAILED)
 *  - FAILED messages are automatically retried on reconnection
 */
class ChatMessageAdapter(
    private val messages: List<ChatMessage>,
    private val currentUsername: String
) : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage, position: Int) {
            // Set the basic text content
            setMessageContent(message)

            // Position and color based on who sent it
            val isCurrentUser = message.username == currentUsername
            setMessageAlignment(isCurrentUser)
            setMessageColor(message, isCurrentUser)

            // Show status indicator on your own messages
            setStatusIndicator(message, isCurrentUser)

            // Messages are not directly tappable; retry is automatic on reconnection
            binding.cvMessage.setOnClickListener(null)
            binding.cvMessage.isClickable = false
        }

        /** Sets username, content, and timestamp text */
        private fun setMessageContent(message: ChatMessage) {
            binding.tvUsername.text = message.username
            binding.tvContent.text = message.content
            binding.tvTime.text = message.getFormattedTime()
        }

        /** Left-align other people's messages, right-align yours */
        private fun setMessageAlignment(isCurrentUser: Boolean) {
            val lp = binding.cvMessage.layoutParams as LinearLayout.LayoutParams
            lp.gravity = if (isCurrentUser) Gravity.END else Gravity.START
            binding.cvMessage.layoutParams = lp
        }

        /** Sets the card background color and username visibility */
        private fun setMessageColor(message: ChatMessage, isCurrentUser: Boolean) {
            binding.apply {
                when {
                    // System messages: green, show "SYSTEM" label
                    message.isSystemMessage -> {
                        cvMessage.setCardBackgroundColor(color(android.R.color.holo_green_dark))
                        tvUsername.text = "SYSTEM"
                        tvUsername.visibility = View.VISIBLE
                    }
                    // Your messages: blue, hide username
                    isCurrentUser -> {
                        cvMessage.setCardBackgroundColor(color(android.R.color.holo_blue_light))
                        tvUsername.visibility = View.GONE
                    }
                    // Other people's messages: gray, show username
                    else -> {
                        cvMessage.setCardBackgroundColor(color(android.R.color.darker_gray))
                        tvUsername.visibility = View.VISIBLE
                    }
                }
            }
        }

        /** Shows PENDING / SENT / FAILED status on your own messages */
        private fun setStatusIndicator(message: ChatMessage, isCurrentUser: Boolean) {
            binding.apply {
                // Only show status on your own non-system messages
                if (!isCurrentUser || message.isSystemMessage) {
                    tvStatus.visibility = View.GONE
                    cvMessage.alpha = 1.0f
                    return
                }

                tvStatus.visibility = View.VISIBLE
                cvMessage.alpha = 1.0f

                when (message.status) {
                    // Sending... dim the card a bit
                    MessageStatus.PENDING -> {
                        tvStatus.text = "⏳ Sending"
                        tvStatus.setTextColor(color(android.R.color.holo_orange_light))
                        cvMessage.alpha = 0.7f
                    }
                    // Sent! show a checkmark
                    MessageStatus.SENT -> {
                        tvStatus.text = "✓"
                        tvStatus.setTextColor(color(android.R.color.white))
                    }
                    // Failed — will be auto-retried on next reconnection
                    MessageStatus.FAILED -> {
                        tvStatus.text = "✗ Failed"
                        tvStatus.setTextColor(color(android.R.color.holo_red_light))
                        cvMessage.setCardBackgroundColor(color(android.R.color.holo_red_dark))
                    }
                }
            }
        }

        /** Helper to get a color from resources */
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