package com.example.grpc_poc_shoutbox.chatFragment

import android.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.grpc_poc_shoutbox.databinding.ItemMessageBinding
import com.example.grpc_poc_shoutbox.dto.ChatMessage

class ChatMessageAdapter(
    private val messages: List<ChatMessage>,
    private val currentUsername: String
) : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            binding.apply {
                tvUsername.text = message.username
                tvContent.text = message.content
                tvTime.text = message.getFormattedTime()

                val isCurrentUser = message.username == currentUsername

                if (isCurrentUser) {
                    cvMessage.setBackgroundColor(
                        itemView.context.getColor(R.color.holo_blue_light)
                    )
                    tvUsername.visibility = View.GONE
                } else {
                    cvMessage.setBackgroundColor(
                        itemView.context.getColor(R.color.darker_gray)
                    )
                    tvUsername.visibility = View.VISIBLE
                }

                if (message.isSystemMessage) {
                    cvMessage.setBackgroundColor(
                        itemView.context.getColor(R.color.holo_green_dark)
                    )
                    tvUsername.text = "SYSTEM"
                    tvUsername.visibility = View.VISIBLE
                }
            }
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
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size
}