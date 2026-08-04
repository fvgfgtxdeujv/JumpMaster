package com.jumpmaster.app.core.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jumpmaster.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that drives the wireless-debugging pairing flow,
 * mirroring Shizuku's UX:
 *
 *  1. Discovers `_adb-tls-pairing._tcp` on this device via mDNS.
 *  2. Shows a notification asking the user to type the 6-digit pairing code.
 *  3. Pairs with [Kadb.pair], then auto-connects to the discovered
 *     `_adb-tls-connect._tcp` port and exposes it via [AdbConnectionService].
 *
 * The user only ever types the pairing code; host / connect port / pairing
 * port are all discovered automatically.
 */
@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    private var adbMdns: AdbMdns? = null

    /** Pairing endpoint discovered via mDNS (waiting for code input). */
    private var pairingEndpoint: MdnsEndpoint? = null

    /** Connect endpoint discovered via mDNS. */
    private var connectEndpoint: MdnsEndpoint? = null

    private val endpointObserver = { endpoint: MdnsEndpoint ->
        Log.i(TAG, "Discovered endpoint: ${endpoint.serviceType} ${endpoint.host}:${endpoint.port}")
        when {
            endpoint.isPairing && pairingEndpoint == null -> {
                pairingEndpoint = endpoint
                val code = lastPairingCode
                // If the user already replied before the pairing port was found,
                // pair immediately without asking for the code again.
                if (code != null) {
                    pair(endpoint, code)
                } else {
                    notifyInputPairingCode(endpoint)
                }
            }
            !endpoint.isPairing -> {
                connectEndpoint = endpoint
                // If pairing already succeeded, connect immediately.
                if (paired && connectEndpoint != null) {
                    startConnect(connectEndpoint!!)
                }
            }
        }
    }

    private var paired = false
    private var lastPairingCode: String? = null
    private val pairingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> onStart()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(EXTRA_REMOTE_INPUT_RESULT)?.toString()
                    ?: ""
                onInput(code)
            }
            ACTION_STOP -> {
                stopSelf()
                null
            }
            else -> return START_NOT_STICKY
        }
        if (notification != null) {
            try {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } catch (e: Throwable) {
                Log.e(TAG, "startForeground failed", e)
                getSystemService(NotificationManager::class.java)
                    .notify(notificationId, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        pairingScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Flow ──

    private fun onStart(): Notification {
        if (!paired) {
            // Fresh start (including a retry after a failed pairing).
            pairingEndpoint = null
            connectEndpoint = null
            lastPairingCode = null
            startDiscovery()
        }
        return searchingNotification()
    }

    private fun startDiscovery() {
        adbMdns?.stop()
        adbMdns = AdbMdns(this, endpointObserver, endpointObserver).apply { start() }
    }

    private fun stopDiscovery() {
        adbMdns?.stop()
        adbMdns = null
    }

    private fun onInput(code: String): Notification {
        lastPairingCode = code
        val endpoint = pairingEndpoint
        if (endpoint == null) {
            // User replied but pairing port is not (yet) known.
            return searchingNotification()
        }
        pair(endpoint, code)
        return workingNotification()
    }

    private fun pair(endpoint: MdnsEndpoint, code: String) {
        val host = endpoint.host
        val port = endpoint.port
        Log.i(TAG, "Pairing with $host:$port")
        pairingScope.launch(Dispatchers.IO) {
            val success = runCatching {
                com.flyfishxu.kadb.Kadb.pair(host, port, code)
            }.onFailure {
                Log.e(TAG, "Pairing failed", it)
            }.isSuccess

            runOnUiThread {
                if (success) {
                    paired = true
                    Log.i(TAG, "Paired successfully with $host:$port")
                    val connect = connectEndpoint
                    if (connect != null) {
                        startConnect(connect)
                    } else {
                        notifyPairingSucceeded()
                    }
                } else {
                    notifyPairingFailed()
                }
            }
        }
    }

    private fun startConnect(endpoint: MdnsEndpoint) {
        Log.i(TAG, "Connecting to ${endpoint.host}:${endpoint.port}")
        val adbService = AdbConnectionService.getInstance()
        adbService.listener = object : AdbConnectionService.ConnectionListener {
            override fun onStateChanged(state: AdbConnectionService.ConnectionState) {
                when (state) {
                    AdbConnectionService.ConnectionState.CONNECTED -> {
                        adbService.listener = null
                        notifyConnected(endpoint)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    AdbConnectionService.ConnectionState.ERROR -> {
                        adbService.listener = null
                        notifyConnectFailed()
                    }
                    else -> {}
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Connect error: $message")
            }
        }
        // Pairing already succeeded above, so connect without re-pairing.
        adbService.connect(host = endpoint.host, port = endpoint.port)
    }

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    // ── Notifications ──

    private fun searchingNotification(): Notification {
        val stopPending = PendingIntent.getService(
            this,
            stopRequestId,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification_accent))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_title))
            .setContentText(getString(R.string.notification_adb_pairing_searching_text))
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_adb_pairing_stop),
                    stopPending
                ).build()
            )
            .build()
    }

    private fun notifyInputPairingCode(endpoint: MdnsEndpoint) {
        val remoteInput = RemoteInput.Builder(EXTRA_REMOTE_INPUT_RESULT)
            .setLabel(getString(R.string.notification_adb_pairing_input_hint))
            .build()
        val replyPending = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification_accent))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_adb_pairing_found_title))
            .setContentText(getString(R.string.notification_adb_pairing_found_text))
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_adb_pairing_input),
                    replyPending
                ).addRemoteInput(remoteInput).build()
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun workingNotification(): Notification {
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification_accent))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setContentText(getString(R.string.notification_adb_pairing_working_text))
            .build()
    }

    private fun notifyPairingSucceeded() {
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification_success))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.notification_adb_pairing_succeed_title))
                .setContentText(getString(R.string.notification_adb_pairing_succeed_text))
                .build()
        )
    }

    private fun notifyPairingFailed() {
        val retryPending = PendingIntent.getService(
            this,
            retryRequestId,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_START),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification_error))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.notification_adb_pairing_failed_title))
                .setContentText(getString(R.string.notification_adb_pairing_failed_text))
                .addAction(
                    Notification.Action.Builder(
                        null,
                        getString(R.string.notification_adb_pairing_retry),
                        retryPending
                    ).build()
                )
                .build()
        )
    }

    private fun notifyConnected(endpoint: MdnsEndpoint) {
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification_success))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.notification_adb_connected_title))
                .setContentText(getString(R.string.notification_adb_connected_text))
                .build()
        )
    }

    private fun notifyConnectFailed() {
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification_error))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.notification_adb_connect_failed_title))
                .setContentText(getString(R.string.notification_adb_connect_failed_text))
                .build()
        )
    }

    companion object {
        private const val TAG = "AdbPairingService"

        const val notificationChannel = "adb_pairing"

        private const val notificationId = 1
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3

        const val ACTION_START = "start"
        const val ACTION_REPLY = "reply"
        const val ACTION_STOP = "stop"
        const val EXTRA_REMOTE_INPUT_RESULT = "pairing_code"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)
        }
    }
}
