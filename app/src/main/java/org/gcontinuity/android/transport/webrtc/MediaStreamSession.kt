package org.gcontinuity.android.transport.webrtc

import android.util.Log
import org.webrtc.MediaStream
import org.webrtc.RtpReceiver
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaStreamSession"

/**
 * Handles incoming remote video/audio [MediaStream] tracks from WebRTC.
 *
 * **Phase 2**: logs track metadata only — no rendering or processing.
 * **Phase 5**: attach the screen-share track to a [org.webrtc.SurfaceViewRenderer].
 * **Phase 6**: push camera frames to a VirtualDisplay for camera-as-webcam.
 *
 * Injected as a singleton so Phase 5/6 can add a render target without
 * changing [WebRtcManager].
 */
@Singleton
class MediaStreamSession @Inject constructor() {

    /**
     * Called by [WebRtcManager] when a remote track is received on any session.
     *
     * @param receiver  The [RtpReceiver] holding the incoming track.
     * @param streams   Associated [MediaStream] wrappers.
     */
    fun onTrackReceived(receiver: RtpReceiver, streams: Array<out MediaStream>) {
        val track = receiver.track()
        Log.i(TAG, "Remote track: kind=${track?.kind()}, id=${track?.id()}, " +
            "streams=${streams.map { it.id }}")
        // Phase 5: attach track to SurfaceViewRenderer for screen share
        // Phase 6: push frames to VirtualDisplay for camera-as-webcam
    }
}
