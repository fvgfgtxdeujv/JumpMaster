package com.jumpmaster.app.core.adb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.*

/**
 * Service that manages ADB wireless debugging connections and provides
 * high-level APIs for screen capture and input simulation.
 *
 * This replaces the PC-side ADB commands from the original Python tool:
 * - `adb shell screencap -p` → captureScreen()
 * - `adb shell input swipe x y x y duration` → performLongPress()
 * - `adb shell wm size` → getScreenSize()
 */
class AdbConnectionService {

    companion object {
        private const val TAG = "AdbConnectionService"
        private var instance: AdbConnectionService? = null

        // Compose-observable state
        val connectionState = mutableStateOf(ConnectionState.DISCONNECTED)

        fun getInstance(): AdbConnectionService {
            if (instance == null) {
                instance = AdbConnectionService()
            }
            return instance!!
        }
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    interface ConnectionListener {
        fun onStateChanged(state: ConnectionState)
        fun onError(message: String)
    }

    private var adbClient: Kadb? = null
    private var state = ConnectionState.DISCONNECTED
    var listener: ConnectionListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastHost: String = ""
    private var lastPort: Int = 0
    private var lastPairingCode: String? = null
    private var lastPairingPort: Int? = null

    fun getConnectionState(): ConnectionState = state

    /**
     * Connect to a device via wireless ADB.
     * @param host Device IP address (e.g., "192.168.1.100")
     * @param port Wireless debugging port
     * @param pairingCode Optional pairing code
     * @param pairingPort Optional pairing port
     */
    fun connect(host: String, port: Int, pairingCode: String? = null, pairingPort: Int? = null) {
        scope.launch {
            val success = ensureConnected(host, port, pairingCode, pairingPort, notifyErrors = true)
            if (!success) {
                Log.e(TAG, "ADB async connect failed to $host:$port")
            }
        }
    }

    /**
     * The last known connection target (host + port), if any.
     * Set when a successful mDNS discovery + pairing flow runs.
     */
    fun getConnectionTarget(): Pair<String, Int>? {
        if (lastHost.isBlank() || lastPort == 0) return null
        return lastHost to lastPort
    }

    suspend fun ensureConnected(
        host: String = lastHost,
        port: Int = lastPort,
        pairingCode: String? = lastPairingCode,
        pairingPort: Int? = lastPairingPort,
        notifyErrors: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank() || port == 0) return@withContext false

        lastHost = host
        lastPort = port
        lastPairingCode = pairingCode
        lastPairingPort = pairingPort

        adbClient?.let { existing ->
            if (probe(existing)) {
                setState(ConnectionState.CONNECTED)
                return@withContext true
            }
            Log.w(TAG, "Existing ADB connection is stale, recreating")
            runCatching { existing.close() }
            adbClient = null
        }

        setState(ConnectionState.CONNECTING)
        Log.i(TAG, "Connecting to $host:$port (pairing=${pairingCode != null})")

        if (!pairingCode.isNullOrBlank() && pairingPort != null && pairingPort > 0) {
            Log.i(TAG, "Pairing with $host:$pairingPort ...")
            val pairResult = runCatching {
                Kadb.pair(host, pairingPort, pairingCode)
            }.onFailure {
                Log.e(TAG, "Pairing failed", it)
            }.isSuccess
            Log.i(TAG, "Pair result: $pairResult")
            if (!pairResult) {
                setState(ConnectionState.ERROR)
                if (notifyErrors) {
                    withContext(Dispatchers.Main) {
                        listener?.onError("ADB配对失败: $host:$pairingPort\n请确认配对码和配对端口正确")
                    }
                }
                return@withContext false
            }
            delay(500)
        }

        Log.i(TAG, "Connecting to ADB daemon at $host:$port ...")
        val client = Kadb.create(
            host = host,
            port = port,
            connectTimeout = 10_000,
            socketTimeout = 10_000
        )
        val success = probe(client)

        if (success) {
            adbClient = client
            setState(ConnectionState.CONNECTED)
            Log.i(TAG, "ADB connected to $host:$port")
            true
        } else {
            runCatching { client.close() }
            setState(ConnectionState.ERROR)
            Log.e(TAG, "ADB connection failed to $host:$port")
            if (notifyErrors) {
                withContext(Dispatchers.Main) {
                    listener?.onError("ADB连接失败: $host:$port\n请确认无线调试已开启且端口正确")
                }
            }
            false
        }
    }

    /**
     * Disconnect from the device.
     */
    fun disconnect() {
        adbClient?.close()
        adbClient = null
        setState(ConnectionState.DISCONNECTED)
    }

    /**
     * Check if connected.
     */
    fun isConnected(): Boolean = adbClient != null && state == ConnectionState.CONNECTED

    /**
     * Capture screenshot via `adb shell screencap -p`.
     * Returns the screenshot as a Bitmap, or null on failure.
     */
    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        val client = adbClient ?: return@withContext null
        if (!client.connectionCheck()) return@withContext null

        try {
            // screencap -p outputs PNG to stdout
            val pngData = client.open("shell:screencap -p").use { stream ->
                stream.source.readByteArray()
            }
            if (pngData.isEmpty()) return@withContext null

            // Remove any trailing newlines/null bytes that the shell may add
            val cleanData = stripTrailingGarbage(pngData)

            val bitmap = BitmapFactory.decodeByteArray(cleanData, 0, cleanData.size)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode screenshot PNG (${cleanData.size} bytes)")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed", e)
            null
        }
    }

    /**
     * Perform a long press via `adb shell input swipe x y x y duration`.
     * This replicates the ADB input command from the original Python tool.
     */
    suspend fun performLongPress(
        x: Int,
        y: Int,
        durationMs: Long,
        endX: Int = x,
        endY: Int = y
    ): Boolean = withContext(Dispatchers.IO) {
        val client = adbClient ?: return@withContext false
        if (!client.connectionCheck()) return@withContext false

        try {
            // "input swipe x y x y duration" simulates a long press when start == end
            val result = client.shell("input swipe $x $y $endX $endY $durationMs").allOutput
            Log.d(TAG, "Long press ($x, $y) -> ($endX, $endY) for ${durationMs}ms: $result")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Long press failed", e)
            false
        }
    }

    /**
     * Get device screen size via `adb shell wm size`.
     * Returns "WIDTHxHEIGHT" (e.g., "1080x1920") or null.
     */
    suspend fun getScreenSize(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val client = adbClient ?: return@withContext null
        val output = client.shell("wm size").allOutput

        // Parse "Physical size: 1080x1920" or "Override size: ..."
        val regex = Regex("""(\d+)x(\d+)""")
        val match = regex.find(output) ?: return@withContext null
        val width = match.groupValues[1].toIntOrNull() ?: return@withContext null
        val height = match.groupValues[2].toIntOrNull() ?: return@withContext null
        width to height
    }

    /**
     * Get device screen density via `adb shell wm density`.
     */
    suspend fun getScreenDensity(): Int? = withContext(Dispatchers.IO) {
        val client = adbClient ?: return@withContext null
        val output = client.shell("wm density").allOutput

        val regex = Regex("""(\d+)""")
        val match = regex.find(output) ?: return@withContext null
        match.groupValues[1].toIntOrNull()
    }

    /**
     * Get device model info via `adb shell getprop ro.product.model`.
     */
    suspend fun getDeviceModel(): String? = withContext(Dispatchers.IO) {
        val client = adbClient ?: return@withContext null
        client.shell("getprop ro.product.model").allOutput.trim()
    }

    private fun probe(client: Kadb): Boolean {
        return runCatching {
            client.shell("echo ok").allOutput.trim() == "ok"
        }.onFailure {
            Log.w(TAG, "ADB probe failed", it)
        }.getOrDefault(false)
    }

    /**
     * Strip trailing garbage bytes from screencap output.
     * Sometimes ADB shell adds trailing newlines or null bytes.
     */
    private fun stripTrailingGarbage(data: ByteArray): ByteArray {
        var end = data.size
        // PNG ends with IEND chunk (00 00 00 00 49 45 4E 44 AE 42 60 82)
        // Find the IEND marker
        for (i in data.size - 1 downTo 12) {
            if (data[i] == 0x82.toByte() &&
                data[i - 1] == 0x60.toByte() &&
                data[i - 2] == 0x42.toByte() &&
                data[i - 3] == 0xAE.toByte()
            ) {
                end = i + 1
                break
            }
        }
        return if (end < data.size) data.copyOf(end) else data
    }

    private fun setState(newState: ConnectionState) {
        state = newState
        connectionState.value = newState
        scope.launch(Dispatchers.Main) {
            listener?.onStateChanged(newState)
        }
    }

    fun destroy() {
        disconnect()
        scope.cancel()
        instance = null
    }
}
