package org.gcontinuity.android.transport.model

/**
 * Represents the connection state of the WebSocket control channel to the Linux daemon.
 *
 * Used by [TransportManager] and observed by [ConnectionViewModel] to drive UI updates
 * and notification text.
 */
enum class ConnectionState {
    /** No connection attempt in progress; no active socket. */
    DISCONNECTED,

    /** TCP + TLS handshake in progress. */
    CONNECTING,

    /** WebSocket open, Hello/Ack exchange complete. */
    CONNECTED,

    /** Previous connection was lost; exponential backoff reconnect in progress. */
    RECONNECTING;

    /** True only when the control channel is fully operational. */
    val isActive: Boolean get() = this == CONNECTED

    /** Human-readable label suitable for notification text and UI chips. */
    val label: String
        get() = when (this) {
            DISCONNECTED  -> "Disconnected"
            CONNECTING    -> "Connecting…"
            CONNECTED     -> "Connected"
            RECONNECTING  -> "Reconnecting…"
        }
}
