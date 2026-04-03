package org.gcontinuity.android.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class Packet {

    @Serializable
    @SerialName("HELLO")
    data class Hello(
        val device_id: String,
        val name: String,
        val version: Int,
        val fingerprint: String
    ) : Packet()

    @Serializable
    @SerialName("PAIR_REQUEST")
    data class PairRequest(
        val device_id: String,
        val name: String,
        val fingerprint: String
    ) : Packet()

    @Serializable
    @SerialName("PAIR_ACCEPT")
    data class PairAccept(val fingerprint: String) : Packet()

    @Serializable
    @SerialName("PAIR_REJECT")
    data class PairReject(val reason: String) : Packet()

    @Serializable
    @SerialName("PING")
    data class Ping(val timestamp_ms: Long) : Packet()

    @Serializable
    @SerialName("PONG")
    data class Pong(val timestamp_ms: Long) : Packet()

    @Serializable
    @SerialName("DISCONNECT")
    data class Disconnect(val reason: String) : Packet()

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        fun fromJson(s: String): Packet? = try {
            json.decodeFromString<Packet>(s)
        } catch (e: Exception) {
            null
        }
    }
}

fun Packet.toJson(): String = Packet.json.encodeToString(this)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
    val host: String = "",
    val port: Int = 52000
)
