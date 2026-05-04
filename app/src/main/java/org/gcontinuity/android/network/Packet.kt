// org/gcontinuity/android/network/Packet.kt
//
// CHANGES FROM OLD VERSION (inferred from WsClient.kt and PairingManager.kt usage):
//   1. Type discriminator was "SCREAMING_SNAKE_CASE" (e.g. "HELLO") →
//      changed to snake_case (e.g. "hello") to match Linux gcontinuity-common.
//   2. Ping/Pong no longer carry timestamp_ms — now bare objects.
//   3. Disconnect no longer carries reason — now a bare object.
//   4. Hello no longer carries fingerprint — fingerprint goes in PairRequest.
//      Android sends Hello first, then PairRequest with the fingerprint.
//   5. toJson() now uses a stable serialization that is safe to call from
//      any thread (no shared state).
//
// PROTOCOL FLOW (Android initiates):
//   Android → Linux : Hello {device_id, name, version}
//   Linux → Android : Hello {device_id, name, version}   ← Linux's identity
//   Android → Linux : PairRequest {device_id, name, fingerprint}
//   Linux → Android : PairAccept {fingerprint}  OR  PairReject {reason}
//   (subsequent keepalive)
//   Either side     : Ping  →  Pong

package org.gcontinuity.android.network

import org.json.JSONObject

/**
 * Phase 1 pairing-layer packet.
 *
 * Serialised as `{"type":"<snake_case>", ...}`.
 * Must stay in sync with `gcontinuity-common/src/packet.rs` on the Linux side.
 */
sealed class Packet {

    // ── Handshake ─────────────────────────────────────────────────────────────

    /** First packet sent by either side after TLS connection is established. */
    data class Hello(
        val device_id: String,
        val name: String,
        val version: Int,
    ) : Packet()

    /**
     * Sent by Android after receiving Linux's Hello, carrying the fingerprint
     * to be verified visually by both users during the pairing ceremony.
     */
    data class PairRequest(
        val device_id: String,
        val name: String,
        val fingerprint: String,
    ) : Packet()

    /** Sent by the accepting side after the user clicks "Accept". */
    data class PairAccept(val fingerprint: String) : Packet()

    /** Sent by either side to cancel or reject pairing. */
    data class PairReject(val reason: String) : Packet()

    // ── Keepalive ─────────────────────────────────────────────────────────────

    /** Keepalive probe — no fields. */
    object Ping : Packet()

    /** Reply to Ping — no fields. */
    object Pong : Packet()

    // ── Teardown ──────────────────────────────────────────────────────────────

    /** Graceful connection teardown — no fields. */
    object Disconnect : Packet()

    // ── Serialisation ─────────────────────────────────────────────────────────

    companion object {
        /**
         * Deserialise a JSON string into a [Packet].
         * Returns `null` for unrecognised or malformed input (forward-compatible).
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
                else          -> {
                    android.util.Log.w("Packet", "Unknown packet type: $type")
                    null
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
 * Uses plain [JSONObject] — no reflection, safe on all API levels.
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
        is Packet.PairAccept -> {
            put("type", "pair_accept")
            put("fingerprint", p.fingerprint)
        }
        is Packet.PairReject -> {
            put("type", "pair_reject")
            put("reason", p.reason)
        }
        Packet.Ping       -> put("type", "ping")
        Packet.Pong       -> put("type", "pong")
        Packet.Disconnect -> put("type", "disconnect")
    }
}.toString()
