package org.grapheneos.apps.client.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class KeepAppActive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

}