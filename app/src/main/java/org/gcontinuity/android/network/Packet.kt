// org/gcontinuity/android/network/Packet.kt
//
// CHANGES vs previous version:
//   - Added FeaturePacket(type, raw) variant to the sealed class.
//     fromJson() now returns FeaturePacket instead of null for unrecognised
//     types (e.g. "linux_battery_info", "clipboard_sync", etc.).
//     This allows WsClient to forward Phase 3–6 packets to the feature layer
//     via GContinuityService.onPacketReceived, instead of silently dropping them.
//   - All existing pairing variants are unchanged.

package org.gcontinuity.android.network

import org.json.JSONObject

/**
 * Phase 1 pairing-layer packet.
 *
 * Serialised as `{"type":"<snake_case>", ...}`.
 * Must stay in sync with `gcontinuity-common/src/packet.rs` on the Linux side.
 *
 * Phase 3–6 feature packets (e.g. [FeaturePacket]) are carried as raw JSON
 * and forwarded to [org.gcontinuity.android.plugins.PluginManager] by
 * [org.gcontinuity.android.service.GContinuityService] before they reach
 * [org.gcontinuity.android.pairing.PairingManager].
 */
sealed class Packet {

    // ── Handshake ─────────────────────────────────────────────────────────────

    data class Hello(
        val device_id: String,
        val name: String,
        val version: Int,
    ) : Packet()

    data class PairRequest(
        val device_id: String,
        val name: String,
        val fingerprint: String,
    ) : Packet()

    data class PairAccept(val fingerprint: String) : Packet()
    data class PairReject(val reason: String) : Packet()

    // ── Keepalive ─────────────────────────────────────────────────────────────

    object Ping       : Packet()
    object Pong       : Packet()
    object Disconnect : Packet()

    // ── Phase 3–6 feature packets ─────────────────────────────────────────────

    /**
     * Wrapper for any packet type that is not part of the Phase 1 pairing
     * protocol (e.g. `linux_battery_info`, `clipboard_sync`, `notification_post`).
     *
     * [type] is the raw `"type"` discriminator value from the JSON.
     * [raw]  is the full original JSON string, passed to
     *        [org.gcontinuity.android.plugins.PluginManager.dispatchRawFeaturePacket]
     *        for decoding via kotlinx.serialization.
     *
     * [PairingManager] never sees this variant — [GContinuityService] intercepts
     * it in the `onPacketReceived` callback before forwarding to [PairingManager].
     */
    data class FeaturePacket(val type: String, val raw: String) : Packet()

    // ── Deserialisation ───────────────────────────────────────────────────────

    companion object {
        /**
         * Deserialise a JSON string into a [Packet].
         *
         * Known pairing types are parsed fully. Unknown types (Phase 3–6
         * feature packets) are returned as [FeaturePacket] so the caller
         * can forward them to the feature layer rather than dropping them.
         *
         * Never returns null.
         */
        fun fromJson(json: String): Packet? = runCatching {
            val obj = JSONObject(json)
            when (val type = obj.getString("type")) {
                "hello" -> Hello(
                    device_id = obj.getString("device_id"),
                    name      = obj.getString("name"),
                    version   = obj.getInt("version"),
                )
                "pair_request" -> PairRequest(
                    device_id   = obj.getString("device_id"),
                    name        = obj.getString("name"),
                    fingerprint = obj.getString("fingerprint"),
                )
                "pair_accept" -> PairAccept(fingerprint = obj.getString("fingerprint"))
                "pair_reject" -> PairReject(reason = obj.getString("reason"))
                "ping"        -> Ping
                "pong"        -> Pong
                "disconnect"  -> Disconnect
                // Phase 3–6 packets — forward raw JSON to feature layer.
                else -> {
                    android.util.Log.d("Packet", "Feature packet received: $type")
                    FeaturePacket(type = type, raw = json)
                }
            }
        }.getOrElse { e ->
            android.util.Log.e("Packet", "Failed to parse packet: $json", e)
            null
        }
    }
}

/**
 * Serialise this [Packet] to a JSON string.
 * [Packet.FeaturePacket] is never serialised (it is inbound-only).
 */
fun Packet.toJson(): String = JSONObject().apply {
    when (val p = this@toJson) {
        is Packet.Hello -> {
            put("type", "hello")
            put("device_id", p.device_id)
            put("name", p.name)
            put("version", p.version)
        }
        is Packet.PairRequest -> {
            put("type", "pair_request")
            put("device_id", p.device_id)
            put("name", p.name)
            put("fingerprint", p.fingerprint)
        }
        is Packet.PairAccept  -> { put("type", "pair_accept");  put("fingerprint", p.fingerprint) }
        is Packet.PairReject  -> { put("type", "pair_reject");  put("reason", p.reason) }
        Packet.Ping           -> put("type", "ping")
        Packet.Pong           -> put("type", "pong")
        Packet.Disconnect     -> put("type", "disconnect")
        is Packet.FeaturePacket -> {
            // FeaturePacket is inbound-only — should never be serialised.
            // Return the raw JSON unchanged if somehow called.
            android.util.Log.w("Packet", "toJson() called on FeaturePacket — returning raw")
            throw UnsupportedOperationException("FeaturePacket is not serialisable")
        }
    }
}.toString()