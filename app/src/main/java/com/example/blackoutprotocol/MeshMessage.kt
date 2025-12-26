package com.example.blackoutprotocol

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

data class MeshMessage(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("content")
    val content: String,

    @SerializedName("senderDeviceId")
    val senderDeviceId: String,

    @SerializedName("senderDeviceName")
    val senderDeviceName: String,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("hops")
    val hops: Int = 0,

    @SerializedName("visitedDevices")
    val visitedDevices: List<String> = emptyList(),

    @SerializedName("isDeliveredToCloud")
    val isDeliveredToCloud: Boolean = false,

    @SerializedName("messageType")
    val messageType: String = "SOS",

    @SerializedName("status")
    val status: String = "IN_TRANSIT",

    @SerializedName("deliveredBy")
    val deliveredBy: String? = null,

    @SerializedName("encryptedLocation")
    val encryptedLocation: String? = null
) {
    companion object {
        const val MAX_HOPS = 5

        fun fromJson(json: String): MeshMessage {
            return try {
                Gson().fromJson(json, MeshMessage::class.java)
            } catch (e: Exception) {
                MeshMessage(
                    content = "Invalid message",
                    senderDeviceId = "unknown",
                    senderDeviceName = "unknown"
                )
            }
        }

        fun createSOSMessage(
            content: String,
            deviceId: String,
            deviceName: String,
            encryptedLocation: String? = null
        ): MeshMessage {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val fullContent = """
                --- SOS ALERT ---
                Time: $time
                Device: $deviceName
                ID: ${deviceId.takeLast(6)}
                Message: $content
                
                ${if (encryptedLocation != null) "📍 Location: Encrypted (Gateway only)" else "📍 Location: Not available"}

                This message is relayed via mesh network.
                Hops: 0 | Type: Emergency
            """.trimIndent()

            return MeshMessage(
                content = fullContent,
                senderDeviceId = deviceId,
                senderDeviceName = deviceName,
                messageType = "SOS",
                timestamp = System.currentTimeMillis(),
                hops = 0,
                visitedDevices = listOf(deviceId),
                isDeliveredToCloud = false,
                status = "IN_TRANSIT",
                encryptedLocation = encryptedLocation
            )
        }
    }

    fun toJson(): String = Gson().toJson(this)

    fun incrementHop(currentDeviceId: String): MeshMessage {
        val newVisited = visitedDevices.toMutableList().apply {
            add(currentDeviceId)
        }
        return this.copy(
            hops = hops + 1,
            visitedDevices = newVisited,
            status = if (hops + 1 >= MAX_HOPS) "MAX_HOPS_REACHED" else "IN_TRANSIT"
        )
    }

    fun markAsDelivered(gatewayDeviceName: String): MeshMessage {
        return this.copy(
            isDeliveredToCloud = true,
            status = "DELIVERED_TO_CLOUD",
            deliveredBy = gatewayDeviceName
        )
    }

    override fun toString(): String {
        val hopsStatus = if (hops >= MAX_HOPS) "⛔ MAX" else "$hops/$MAX_HOPS"
        val cloudStatus = if (isDeliveredToCloud) "✅ CLOUD" else "⏳ MESH"
        val gatewayInfo = deliveredBy?.let { " by $it" } ?: ""
        return "[$messageType] Hops: $hopsStatus | $cloudStatus$gatewayInfo | From: ${senderDeviceName.take(10)} | ${content.take(50)}..."
    }

    fun toDisplayString(): String {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val hopsStatus = if (hops >= MAX_HOPS) "⛔ $hops/$MAX_HOPS (MAX)" else "$hops/$MAX_HOPS"
        return """
            📨 Message ID: ${id.take(8)}
            👤 From: $senderDeviceName
            🕒 Time: ${timeFormat.format(Date(timestamp))}
            🔄 Hops: $hopsStatus
            ☁️ Cloud: ${if (isDeliveredToCloud) "✅ Delivered" else "⏳ In Mesh"}
            ${if (deliveredBy != null) "🔵 Gateway: $deliveredBy\n" else ""}
            ${if (encryptedLocation != null) "📍 Location: Encrypted (Gateway access only)\n" else ""}
            📝 Content:
            $content
        """.trimIndent()
    }
}