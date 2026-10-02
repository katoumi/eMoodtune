package com.example.moodsync

import android.util.Base64
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.firebase.firestore.FirebaseFirestore

object ProfileImageLoader {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun load(imageView: ImageView) {
        val uid = FirebaseAuthHelper.currentUserId() ?: return
        
        imageView.setImageResource(R.drawable.ic_launcher_foreground)

        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                // Priority 1: Base64 (New Workaround)
                val base64 = document.getString("profileImageBase64")
                if (!base64.isNullOrBlank()) {
                    loadFromBase64(imageView, base64)
                    return@addOnSuccessListener
                }

                // Priority 2: URL (Legacy/Fallback)
                val url = document.getString("profileImageUrl")
                if (!url.isNullOrBlank()) {
                    loadFromUrl(imageView, url)
                }
            }
    }

    fun loadFromUrl(imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_launcher_foreground)
            return
        }

        Glide.with(imageView.context)
            .load(url)
            .placeholder(R.drawable.ic_launcher_foreground)
            .error(R.drawable.ic_launcher_foreground)
            .circleCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(imageView)
    }

    fun loadFromBase64(imageView: ImageView, base64: String?) {
        if (base64.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_launcher_foreground)
            return
        }

        try {
            val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
            Glide.with(imageView.context)
                .asBitmap()
                .load(decodedBytes)
                .placeholder(R.drawable.ic_launcher_foreground)
                .error(R.drawable.ic_launcher_foreground)
                .circleCrop()
                .transition(BitmapTransitionOptions.withCrossFade())
                .into(imageView)
        } catch (_: Exception) {
            imageView.setImageResource(R.drawable.ic_launcher_foreground)
        }
    }
}
