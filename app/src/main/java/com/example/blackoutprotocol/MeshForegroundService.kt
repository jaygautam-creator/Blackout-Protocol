package com.example.blackoutprotocol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "mesh_network_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_MESH"
        const val ACTION_STOP = "STOP_MESH"
    }

    private lateinit var networkManager: NetworkManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        networkManager = NetworkManager(this)

        // Set up minimal listeners for service
        networkManager.setListeners(
            onMessageReceived = { message ->
                Log.d(TAG, "Service received message: ${message.id}")
            },
            onStatusUpdate = { status ->
                Log.d(TAG, "Service status: $status")
                updateNotification(status)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")

        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopMesh()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startMesh()
                startForeground()
                return START_STICKY
            }
        }
    }

    private fun startForeground() {
        createNotificationChannel()

        val notification = buildNotification("Mesh Network Active")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps mesh network alive in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🔗 Blackout Protocol")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startMesh() {
        try {
            networkManager.startMeshNetwork()
            Log.d(TAG, "Mesh network started from service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh: ${e.message}")
        }
    }

    private fun stopMesh() {
        try {
            networkManager.stop()
            Log.d(TAG, "Mesh network stopped from service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop mesh: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMesh()
        Log.d(TAG, "Service destroyed")
    }
}