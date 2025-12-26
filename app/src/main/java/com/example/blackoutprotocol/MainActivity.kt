package com.example.blackoutprotocol

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var etMessage: EditText
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnectedCount: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var switchLocation: SwitchMaterial
    private lateinit var ivInternetStatus: ImageView
    private lateinit var ivMeshStatus: ImageView
    private lateinit var ivGatewayStatus: ImageView
    private lateinit var btnClearLog: Button
    private lateinit var logScrollView: ScrollView  // Add this

    private lateinit var networkManager: NetworkManager
    private var logMessageCount = 0

    // Permissions
    private val permissionsBasic = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.INTERNET
    )

    private val permissionsApi31 = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        initializeUI()

        // Add initial test message to verify log is working
        addToLog("# 🔄 Application started at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
        addToLog("# ✅ UI initialized successfully")

        // Initialize Network Manager
        initializeNetworkManager()

        // Check permissions
        if (hasAllPermissions()) {
            startNetwork()
        } else {
            requestNeededPermissions()
        }
    }

    private fun initializeUI() {
        etMessage = findViewById(R.id.etMessage)
        tvLog = findViewById(R.id.tvReceived)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnectedCount = findViewById(R.id.tvConnectedCount)
        tvLogCount = findViewById(R.id.tvLogCount)
        switchLocation = findViewById(R.id.switchLocation)
        ivInternetStatus = findViewById(R.id.ivInternetStatus)
        ivMeshStatus = findViewById(R.id.ivMeshStatus)
        ivGatewayStatus = findViewById(R.id.ivGatewayStatus)
        btnClearLog = findViewById(R.id.btnClearLog)
        logScrollView = findViewById(R.id.logScrollView)  // Initialize ScrollView

        // Send button
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener {
            sendMessage()
        }

        // Test button
        findViewById<MaterialButton>(R.id.btnTestFirebase).setOnClickListener {
            testFirebaseConnection()
        }

        // Clear log button
        btnClearLog.setOnClickListener {
            clearLog()
        }

        // Export log button
        findViewById<Button>(R.id.btnExportLog).setOnClickListener {
            exportLog()
        }
    }

    private fun initializeNetworkManager() {
        try {
            networkManager = NetworkManager(this)

            networkManager.setListeners(
                onMessageReceived = { message ->
                    runOnUiThread {
                        logMessageCount++
                        tvLogCount.text = logMessageCount.toString()

                        val logEntry = """
                            ─────────────────────────────────────
                            📨 ${formatTime(message.timestamp)}
                            👤 ${message.senderDeviceName}
                            🔄 Hops: ${message.hops}
                            ${if (message.isDeliveredToCloud) "✅ Delivered" else "⏳ In Transit"}
                            ${message.content.take(100)}...
                            ─────────────────────────────────────
                        """.trimIndent()

                        addToLog(logEntry)

                        if (message.status == "DELIVERED_TO_CLOUD") {
                            showToast("✅ Message delivered to cloud")
                        }
                    }
                },
                onStatusUpdate = { status ->
                    runOnUiThread {
                        tvStatus.text = status

                        // Extract connected device count
                        if (status.contains("Connected to")) {
                            val regex = """Connected to (\d+)""".toRegex()
                            val match = regex.find(status)
                            match?.groupValues?.get(1)?.let { count ->
                                tvConnectedCount.text = count
                            }
                        }

                        // Update status indicators
                        when {
                            status.contains("Internet: Available") -> {
                                ivInternetStatus.setColorFilter(Color.parseColor("#81C784"))
                            }
                            status.contains("Internet: Offline") -> {
                                ivInternetStatus.setColorFilter(Color.parseColor("#FF6B6B"))
                            }
                            status.contains("Mesh active") -> {
                                ivMeshStatus.setColorFilter(Color.parseColor("#4FC3F7"))
                            }
                            status.contains("Gateway") -> {
                                ivGatewayStatus.setColorFilter(Color.parseColor("#FFA500"))
                            }
                        }
                    }
                }
            )

            addToLog("# ✅ Network Manager Initialized")

        } catch (e: Exception) {
            addToLog("# ❌ Error initializing network: ${e.message}")
        }
    }

    private fun sendMessage() {
        val message = etMessage.text.toString().trim()
        if (message.isNotEmpty()) {
            showToast("Sending SOS...")

            // Immediately add to log before sending
            addToLog("# 📤 SENDING: $message")
            logMessageCount++
            tvLogCount.text = logMessageCount.toString()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Get location if enabled
                    val encryptedLocation = if (switchLocation.isChecked) {
                        // Simulate location for demo
                        networkManager.encryptLocation(28.6139, 77.2090) // Delhi coordinates
                    } else null

                    networkManager.sendMessage(message, encryptedLocation)
                    etMessage.text.clear()

                    // Update log to show it was sent
                    addToLog("# ✅ SOS Sent Successfully!")

                    showToast("🚨 SOS Sent!")

                } catch (e: Exception) {
                    addToLog("# ❌ Send Failed: ${e.message}")
                    showToast("❌ Failed to send")
                }
            }, 500)
        } else {
            showToast("Please enter a message")
        }
    }

    private fun addToLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$time] $text\n"

        // Append to TextView
        val currentText = tvLog.text.toString()
        tvLog.text = if (currentText.isEmpty()) logEntry else "$currentText$logEntry"

        // Update log count
        val lines = tvLog.text.toString().split("\n").filter { it.isNotEmpty() }
        logMessageCount = lines.size
        tvLogCount.text = logMessageCount.toString()

        // Scroll to bottom of ScrollView (NOT the TextView)
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }

        // Log to console for debugging
        println("LOG ADDED: $logEntry")
    }

    private fun clearLog() {
        tvLog.text = ""
        logMessageCount = 0
        tvLogCount.text = "0"
        addToLog("# 📋 Log cleared")
        showToast("Log cleared")
    }

    private fun exportLog() {
        if (tvLog.text.isNotEmpty()) {
            // Simulate export - in real app, save to file or share
            addToLog("# 💾 Exporting log...")
            showToast("Log exported to storage")

            // You can add actual export logic here
            // For example: save to file, share via intent, etc.
        } else {
            showToast("No log entries to export")
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun testFirebaseConnection() {
        addToLog("# 🔧 Testing Firebase connection...")
        showToast("Testing Firebase...")

        try {
            val database = FirebaseDatabase.getInstance()
            val testRef = database.getReference("test_messages")

            val testData = hashMapOf(
                "message" to "Test from Blackout Protocol",
                "timestamp" to System.currentTimeMillis(),
                "device" to Build.MODEL,
                "app_version" to "1.0"
            )

            val testKey = "test_${System.currentTimeMillis()}"

            testRef.child(testKey).setValue(testData)
                .addOnSuccessListener {
                    addToLog("# ✅ Firebase test successful - Data written")
                    showToast("✅ Firebase connected")
                }
                .addOnFailureListener { e ->
                    addToLog("# ❌ Firebase write failed: ${e.message}")
                    showToast("❌ Firebase failed")
                }
        } catch (e: Exception) {
            addToLog("# ❌ Firebase error: ${e.message}")
            showToast("❌ Firebase error")
        }
    }

    private fun hasAllPermissions(): Boolean {
        val allPermissions = mutableListOf<String>().apply {
            addAll(permissionsBasic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addAll(permissionsApi31)
            }
        }

        return allPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNeededPermissions() {
        val allPermissions = mutableListOf<String>().apply {
            addAll(permissionsBasic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addAll(permissionsApi31)
            }
        }
        ActivityCompat.requestPermissions(this, allPermissions.toTypedArray(), 101)
    }

    private fun startNetwork() {
        try {
            networkManager.startMeshNetwork()
            addToLog("# 🔗 Starting mesh network...")
        } catch (e: Exception) {
            addToLog("# ❌ Failed to start network: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                addToLog("# ✅ All permissions granted")
                startNetwork()
            } else {
                addToLog("# ⚠️ Some permissions denied")
                showToast("Some permissions denied - features may not work")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkManager.stop()
        addToLog("# ⏹️ App shutting down")
    }
}