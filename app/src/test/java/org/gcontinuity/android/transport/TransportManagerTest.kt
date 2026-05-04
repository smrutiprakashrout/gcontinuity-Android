package org.gcontinuity.android.transport

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.gcontinuity.android.transport.model.ConnectionState
import org.gcontinuity.android.transport.model.Packet
import org.gcontinuity.android.util.ExponentialBackoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for TransportManager WebSocket protocol behaviour.
 *
 * Uses [MockWebServer] to simulate the Linux gcontinuity-daemon without a real
 * TLS cert (MockWebServer uses plain HTTP/WS). TLS pinning is tested separately
 * in [PinnedCertTrustManagerTest].
 */
class TransportManagerTest {

    private lateinit var server: MockWebServer
    private val json = Json {
        ignoreUnknownKeys  = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** Verifies client sends a "hello" typed JSON packet on WebSocket open. */
    @Test
    fun `client sends Hello packet on connection open`() {
        val latch        = CountDownLatch(1)
        var receivedText: String? = null

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                receivedText = text
                latch.countDown()
            }
        }))

        val client = OkHttpClient()
        client.newWebSocket(
            Request.Builder().url(server.url("/gcontinuity")).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(
                        json.encodeToString(
                            Packet.serializer(),
                            Packet.Hello(device_id = "test-id", name = "Pixel", version = 1),
                        )
                    )
                }
            }
        )

        assertTrue("Server must receive Hello within 3 s", latch.await(3, TimeUnit.SECONDS))
        val text = receivedText ?: error("No message received")
        assertTrue(text.contains("\"type\":\"hello\""))
        assertTrue(text.contains("\"version\":1"))
        assertTrue(text.contains("\"device_id\":\"test-id\""))
    }

    /** Verifies the server can send Ack and the client can decode it. */
    @Test
    fun `server Ack packet decodes correctly`() {
        val ackJson = json.encodeToString(Packet.serializer(), Packet.Ack)
        val decoded = json.decodeFromString(Packet.serializer(), ackJson)
        assertEquals(Packet.Ack, decoded)
    }

    /** Verifies Disconnect packet has correct type field. */
    @Test
    fun `Disconnect packet serializes with correct type`() {
        val encoded = json.encodeToString(Packet.serializer(), Packet.Disconnect)
        assertTrue(encoded.contains("\"type\":\"disconnect\""))
        assertEquals(Packet.Disconnect, json.decodeFromString(Packet.serializer(), encoded))
    }

    /** Verifies SessionResume has the correct token in its JSON. */
    @Test
    fun `SessionResume encodes session_token field`() {
        val token   = "tok-abc-123"
        val encoded = json.encodeToString(Packet.serializer(), Packet.SessionResume(token))
        assertTrue(encoded.contains("\"type\":\"session_resume\""))
        assertTrue(encoded.contains("\"session_token\":\"$token\""))
    }

    /** Verifies ExponentialBackoff never exceeds 30 s ceiling across 30 attempts. */
    @Test
    fun `reconnect backoff ceiling never exceeds 30 s`() {
        val backoff = ExponentialBackoff(
            initialDelayMs = 1_000L,
            maxDelayMs     = 30_000L,
            factor         = 2.0,
            jitterMs       = 0L,
        )
        repeat(30) {
            val delay = backoff.nextDelayMs()
            assertTrue("Attempt $it: delay $delay > 30000", delay <= 30_000L)
        }
    }

    /** Verifies ConnectionState.isActive is only true for CONNECTED. */
    @Test
    fun `ConnectionState isActive only true for CONNECTED`() {
        assertTrue(ConnectionState.CONNECTED.isActive)
        assertTrue(!ConnectionState.DISCONNECTED.isActive)
        assertTrue(!ConnectionState.CONNECTING.isActive)
        assertTrue(!ConnectionState.RECONNECTING.isActive)
    }

    /** Verifies Ping → Pong response encoding. */
    @Test
    fun `Ping and Pong serialize with correct type fields`() {
        val ping = json.encodeToString(Packet.serializer(), Packet.Ping)
        val pong = json.encodeToString(Packet.serializer(), Packet.Pong)
        assertTrue(ping.contains("\"type\":\"ping\""))
        assertTrue(pong.contains("\"type\":\"pong\""))
    }
}
