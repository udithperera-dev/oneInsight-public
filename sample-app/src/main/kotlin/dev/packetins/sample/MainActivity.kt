package dev.packetins.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.packetins.sdk.PacketIns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var logView: TextView
    private var webSocket: WebSocket? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(PacketIns.interceptor())
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val api: DemoApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://httpbin.org/")
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(DemoApi::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.logView)

        findViewById<Button>(R.id.btnHttp).setOnClickListener { sendRest() }
        findViewById<Button>(R.id.btnWsConnect).setOnClickListener { connectWs() }
        findViewById<Button>(R.id.btnWsSend).setOnClickListener { sendWs() }
        findViewById<Button>(R.id.btnWsClose).setOnClickListener { closeWs() }
        appendLog("PacketIns sample ready. Start the PacketIns tool window and connect a device.")
    }

    private fun sendRest() {
        io.execute {
            try {
                val getBody = api.getUuid().execute().body()
                appendLog("GET /uuid => $getBody")

                val json = """{"hello":"packetins","ts":${System.currentTimeMillis()}}"""
                val request = Request.Builder()
                    .url("https://httpbin.org/post")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer demo-token")
                    .build()
                client.newCall(request).execute().use { response ->
                    appendLog("POST /post => ${response.code} ${response.body?.string()?.take(240)}")
                }
            } catch (t: Throwable) {
                appendLog("REST error: ${t.message}")
            }
        }
    }

    private fun connectWs() {
        closeWs()
        val request = Request.Builder()
            .url("wss://echo.websocket.events")
            .build()
        val factory = PacketIns.webSocketFactory(client)
        webSocket = factory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                appendLog("WS open: ${response.code}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                appendLog("WS in: $text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("WS closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("WS closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                appendLog("WS failure: ${t.message}")
            }
        })
        appendLog("WS connecting…")
    }

    private fun sendWs() {
        val socket = webSocket
        if (socket == null) {
            appendLog("WS not connected")
            return
        }
        val payload = """{"ping":${System.currentTimeMillis()}}"""
        socket.send(payload)
        appendLog("WS out: $payload")
    }

    private fun closeWs() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            logView.append(message)
            logView.append("\n")
        }
    }

    override fun onDestroy() {
        closeWs()
        io.shutdownNow()
        super.onDestroy()
    }

    private interface DemoApi {
        @GET("uuid")
        fun getUuid(): Call<String>
    }
}
