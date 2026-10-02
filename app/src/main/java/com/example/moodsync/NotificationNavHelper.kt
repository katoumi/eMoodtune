package com.example.moodsync

import android.app.Activity
import android.content.Intent
import android.widget.ImageView

object NotificationNavHelper {

    fun setup(activity: Activity) {
        val imgNotifications = activity.findViewById<ImageView?>(R.id.imgNotifications)

        imgNotifications?.setOnClickListener {
            activity.startActivity(
                Intent(activity, NotificationsActivity::class.java)
            )
        }
    }
}