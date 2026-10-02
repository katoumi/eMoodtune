package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class FeedbackActivity : AppCompatActivity() {

    private lateinit var etFeedback: EditText
    private lateinit var btnSend: Button
    private lateinit var btnBack: Button
    private lateinit var imgProfile: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        etFeedback = findViewById(R.id.etFeedback)
        btnSend = findViewById(R.id.btnSend)
        btnBack = findViewById(R.id.btnBack)
        imgProfile = findViewById(R.id.imgProfile)

        btnSend.setOnClickListener {
            sendFeedback()
        }

        btnBack.setOnClickListener {
            finish()
        }

        imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ProfileImageLoader.load(imgProfile)
    }

    private fun sendFeedback() {
        val feedback = etFeedback.text.toString().trim()

        if (feedback.isEmpty()) {
            Toast.makeText(this, "Please enter your feedback first", Toast.LENGTH_SHORT).show()
            return
        }

        btnSend.isEnabled = false

        FirebaseUserFeedbackHelper.sendFeedback(
            message = feedback,
            onSuccess = {
                runOnUiThread {
                    btnSend.isEnabled = true
                    Toast.makeText(this, "Feedback sent", Toast.LENGTH_SHORT).show()
                    etFeedback.text.clear()
                }
            },
            onError = { error ->
                runOnUiThread {
                    btnSend.isEnabled = true
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}