package org.gcontinuity.android.transport.model

/**
 * Lifecycle state of a single WebRTC peer-connection session, keyed by session_id.
 *
 * Transitions: IDLE → SIGNALING → ICE_CHECKING → ACTIVE → CLOSING → IDLE.
 * FAILED is a terminal state requiring a new session to be created.
 */
enum class TransportState {
    /** No session exists for this session_id. */
    IDLE,

    /** SDP offer/answer exchange in progress. */
    SIGNALING,

    /** ICE candidate gathering and connectivity checks in progress. */
    ICE_CHECKING,

    /** Peer connection established; DataChannel or media tracks are live. */
    ACTIVE,

    /** Closing handshake initiated; waiting for remote acknowledgement. */
    CLOSING,

    /** Connection failed permanently; must create a new session. */
    FAILED,
}
