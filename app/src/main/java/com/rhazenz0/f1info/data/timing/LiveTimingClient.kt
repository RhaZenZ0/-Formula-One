package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.data.net.Http
import com.rhazenz0.f1info.data.net.Http.await
import com.rhazenz0.f1info.util.AppJson
import com.rhazenz0.f1info.util.TimeFormat
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.entries
import com.rhazenz0.f1info.util.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class ConnectionState { Connecting, Connected, Reconnecting, Disconnected }

sealed interface LiveEvent {
    data class Status(val state: ConnectionState, val message: String? = null, val transport: String? = null) : LiveEvent
    /** Full state of every subscribed topic, sent once per connection. */
    data class Initial(val topics: Map<String, JsonElement>, val at: Instant) : LiveEvent
    data class Feed(val topic: String, val data: JsonElement, val at: Instant) : LiveEvent
}

/**
 * Connects to Formula 1's live timing hub (the same feed used by the official live timing
 * app). Most topics are public; CarData.z and Position.z are only sent to clients that pass
 * an F1 TV subscription token.
 */
class LiveTimingClient(
    private val tokenProvider: () -> String? = { null },
    private val baseUrl: String = "https://livetiming.formula1.com/signalrcore",
) {
    companion object {
        val TOPICS = SessionAccumulator.TOPICS + listOf("CarData.z", "Position.z")
        private const val PING_INTERVAL_MS = 15_000L
        private const val SERVER_TIMEOUT_MS = 45_000L
    }

    fun events(topics: List<String> = TOPICS): Flow<LiveEvent> = channelFlow {
        var attempt = 0
        while (isActive) {
            send(LiveEvent.Status(if (attempt == 0) ConnectionState.Connecting else ConnectionState.Reconnecting))
            try {
                runConnection(this, topics) { attempt = 0 }
                send(LiveEvent.Status(ConnectionState.Reconnecting, "Connection closed by server"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(LiveEvent.Status(ConnectionState.Reconnecting, e.message ?: e.javaClass.simpleName))
            }
            attempt++
            delay((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun runConnection(
        out: ProducerScope<LiveEvent>,
        topics: List<String>,
        onConnected: () -> Unit,
    ) = coroutineScope {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        val connectionToken = negotiate(token)
        val transport: Transport = openWebSocket(connectionToken, token)
            ?: LongPollingTransport(Http.client, "$baseUrl?id=$connectionToken", token).also { it.start(this) }

        try {
            transport.send(SignalRProtocol.handshake())
            val handshake = withTimeout(20_000) { transport.receive() }
            val first = SignalRProtocol.frames(handshake).firstOrNull()?.let { SignalRProtocol.parse(it) }
            if (first is SignalRProtocol.Message.HandshakeResponse && first.error != null) {
                throw IOException("Handshake rejected: ${first.error}")
            }
            // Frames that arrived together with the handshake response.
            val extra = SignalRProtocol.frames(handshake).drop(1)

            transport.send(SignalRProtocol.subscribe("1", topics))
            out.send(LiveEvent.Status(ConnectionState.Connected, transport = transport.name))
            onConnected()

            launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    runCatching { transport.send(SignalRProtocol.ping()) }
                }
            }

            extra.forEach { handleFrame(out, it) }
            while (isActive) {
                val payload = withTimeoutOrNull(SERVER_TIMEOUT_MS) { transport.receive() }
                    ?: throw IOException("Live timing stopped responding")
                for (frame in SignalRProtocol.frames(payload)) {
                    if (!handleFrame(out, frame)) return@coroutineScope
                }
            }
        } finally {
            transport.close()
            coroutineContext.cancelChildren()
        }
    }

    /** Returns false when the server closed the connection. */
    private suspend fun handleFrame(out: ProducerScope<LiveEvent>, frame: String): Boolean {
        when (val msg = SignalRProtocol.parse(frame)) {
            is SignalRProtocol.Message.Completion -> {
                if (msg.error != null) throw IOException("Subscribe failed: ${msg.error}")
                val topics = msg.result.entries().associate { (k, v) -> k to decode(k, v) }
                    .filterValues { it != null }.mapValues { it.value!! }
                out.send(LiveEvent.Initial(topics, Instant.now()))
            }
            is SignalRProtocol.Message.Invocation -> {
                if (msg.target.equals("feed", ignoreCase = true) && msg.arguments.size >= 2) {
                    val topic = msg.arguments[0].text() ?: return true
                    val data = decode(topic, msg.arguments[1]) ?: return true
                    val at = TimeFormat.parseUtc(msg.arguments.getOrNull(2).text()) ?: Instant.now()
                    out.send(LiveEvent.Feed(topic, data, at))
                }
            }
            is SignalRProtocol.Message.Close -> return false
            else -> Unit
        }
        return true
    }

    private fun decode(topic: String, value: JsonElement): JsonElement? =
        if (topic.endsWith(".z")) {
            if (value is JsonPrimitive && value.isString) FeedCodec.inflateElement(value) else value
        } else {
            value
        }

    private suspend fun negotiate(token: String?): String {
        val request = Request.Builder()
            .url("$baseUrl/negotiate?negotiateVersion=1")
            .post(ByteArray(0).toRequestBody(null))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        Http.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Live timing negotiate failed (${response.code})")
            val body = AppJson.parseToJsonElement(response.body?.string().orEmpty())
            return body.at("connectionToken").text() ?: body.at("connectionId").text()
                ?: throw IOException("Live timing negotiate returned no connection id")
        }
    }

    private suspend fun openWebSocket(connectionToken: String, token: String?): Transport? {
        val wsUrl = baseUrl.replaceFirst("https://", "wss://") + "?id=$connectionToken"
        val transport = WebSocketTransport(Http.client, wsUrl, token)
        return if (transport.open()) transport else null
    }

    private interface Transport {
        val name: String
        suspend fun send(text: String)
        suspend fun receive(): String
        fun close()
    }

    private class WebSocketTransport(
        private val client: OkHttpClient,
        private val url: String,
        private val token: String?,
    ) : Transport {
        override val name = "WebSocket"
        private val incoming = Channel<String>(Channel.UNLIMITED)
        private val opened = CompletableDeferred<Boolean>()
        private var socket: WebSocket? = null

        suspend fun open(): Boolean {
            val request = Request.Builder().url(url)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            socket = client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
                .newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        incoming.trySend(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        incoming.trySend(bytes.utf8())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        incoming.close(IOException("Live timing closed ($code)"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        incoming.close(IOException("Live timing closed ($code)"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        opened.complete(false)
                        incoming.close(t)
                    }
                })
            val ok = withTimeoutOrNull(15_000) { opened.await() } ?: false
            if (!ok) close()
            return ok
        }

        override suspend fun send(text: String) {
            if (socket?.send(text) != true) throw IOException("Live timing connection lost")
        }

        override suspend fun receive(): String = incoming.receive()

        override fun close() {
            socket?.cancel()
            incoming.close()
        }
    }

    /** Fallback for networks that block WebSockets. */
    private class LongPollingTransport(
        client: OkHttpClient,
        private val url: String,
        private val token: String?,
    ) : Transport {
        override val name = "Long polling"
        private val pollClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        private val sendClient = client
        private val incoming = Channel<String>(Channel.UNLIMITED)

        fun start(scope: CoroutineScope) {
            scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val request = Request.Builder().url(url)
                            .apply { if (token != null) header("Authorization", "Bearer $token") }
                            .build()
                        pollClient.newCall(request).await().use { response ->
                            when {
                                response.code == 204 -> throw IOException("Live timing closed the connection")
                                !response.isSuccessful -> throw IOException("Live timing poll failed (${response.code})")
                                else -> {
                                    val body = response.body?.string().orEmpty()
                                    if (body.isNotEmpty()) incoming.send(body)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    incoming.close()
                    throw e
                } catch (e: Exception) {
                    incoming.close(e)
                }
            }
        }

        override suspend fun send(text: String) {
            val request = Request.Builder().url(url)
                .post(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            sendClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("Live timing send failed (${response.code})")
            }
        }

        override suspend fun receive(): String = incoming.receive()

        override fun close() {
            incoming.close()
        }
    }
}
