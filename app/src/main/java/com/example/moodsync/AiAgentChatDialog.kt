package com.example.moodsync

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AiAgentChatDialog(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onTriggerAction: (action: String, targetMood: String?) -> Unit
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {

    companion object {
        private val sessionMessages = mutableListOf<ChatMessage>()
        
        fun clearSessionHistory() {
            sessionMessages.clear()
        }
    }

    data class ChatMessage(
        val text: String,
        val isUser: Boolean,
        var isTypingAnimated: Boolean = false
    )

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_ai_agent_chat)

        // Slide-Up Window Animation
        window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setWindowAnimations(R.style.DialogAnimation)
        }

        val btnClose = findViewById<ImageView>(R.id.btnCloseAgentChat)
        val btnClear = findViewById<ImageView>(R.id.btnClearAgentChat)
        val etInput = findViewById<EditText>(R.id.etAgentInput)
        val btnSend = findViewById<ImageButton>(R.id.btnAgentSend)
        val recycler = findViewById<RecyclerView>(R.id.recyclerChatMessages)

        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter

        // Load retained session messages if they exist
        if (sessionMessages.isEmpty()) {
            val welcome = ChatMessage("Hello! I am eMoodtune's Companion. How are you feeling today?", isUser = false, isTypingAnimated = false)
            sessionMessages.add(welcome)
        }

        // Copy session messages into current adapter list
        messages.clear()
        messages.addAll(sessionMessages)

        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter

        scrollToBottom()

        btnClose.setOnClickListener { dismiss() }

        btnClear.setOnClickListener {
            clearSessionHistory()
            messages.clear()
            val welcome = ChatMessage("Chat history cleared. How can I help with your mood today?", isUser = false, isTypingAnimated = true)
            messages.add(welcome)
            sessionMessages.add(welcome)
            adapter.notifyDataSetChanged()
            Toast.makeText(context, "Chat history cleared", Toast.LENGTH_SHORT).show()
        }

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotBlank()) {
                etInput.setText("")
                addUserMessage(text)
                processAgentQuery(text)
            }
        }
    }

    private fun addUserMessage(text: String) {
        val msg = ChatMessage(text = text, isUser = true, isTypingAnimated = true)
        sessionMessages.add(msg)
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun addAgentMessage(text: String, animate: Boolean = true) {
        val msg = ChatMessage(text = text, isUser = false, isTypingAnimated = !animate)
        sessionMessages.add(msg)
        messages.add(msg)
        val position = messages.size - 1
        adapter.notifyItemInserted(position)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val recycler = findViewById<RecyclerView>(R.id.recyclerChatMessages)
        if (messages.isNotEmpty()) {
            recycler.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun processAgentQuery(query: String) {
        lifecycleOwner.lifecycleScope.launch {
            val response = eMoodtuneAgentManager.processQuery(context, query)
            addAgentMessage(response.message, animate = true)

            response.actionTriggered?.let { action ->
                onTriggerAction(action, response.targetMood)
            }
        }
    }

    private inner class ChatAdapter(private val items: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val bubbleUser: View = view.findViewById(R.id.bubbleUser)
            val tvUserMessage: TextView = view.findViewById(R.id.tvUserMessage)
            val bubbleAgent: View = view.findViewById(R.id.bubbleAgent)
            val tvAgentMessage: TextView = view.findViewById(R.id.tvAgentMessage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            if (item.isUser) {
                holder.bubbleUser.visibility = View.VISIBLE
                holder.bubbleAgent.visibility = View.GONE
                holder.tvUserMessage.text = item.text
            } else {
                holder.bubbleUser.visibility = View.GONE
                holder.bubbleAgent.visibility = View.VISIBLE

                if (!item.isTypingAnimated) {
                    item.isTypingAnimated = true
                    // Real-time Typewriter Animation
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        val fullText = item.text
                        val builder = StringBuilder()
                        for (char in fullText) {
                            builder.append(char)
                            holder.tvAgentMessage.text = builder.toString()
                            scrollToBottom()
                            delay(16) // 16ms per character typing speed
                        }
                    }
                } else {
                    holder.tvAgentMessage.text = item.text
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
