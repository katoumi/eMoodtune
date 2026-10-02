package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class FaqActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var imgProfile: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)

        btnBack = findViewById(R.id.btnBack)
        imgProfile = findViewById(R.id.imgProfile)

        btnBack.setOnClickListener {
            finish()
        }

        imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ProfileImageLoader.load(imgProfile)
    }
}