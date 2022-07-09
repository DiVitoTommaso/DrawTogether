package com.divito.drawtogether.bluetooth

import android.Manifest.permission.*
import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.*
import android.bluetooth.BluetoothDevice.*
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.*
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity.*
import com.divito.drawtogether.APP_ID
import com.divito.drawtogether.R
import com.divito.drawtogether.WhiteboardActivity
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class BluetoothManager(private val activity: WhiteboardActivity) :
    BroadcastReceiver() {

    enum class ErrorCodes { DISCONNECT, KILLED, TIMEOUT, DISCOVERABLE_PERMISSION_DENIED }

    /** var used to check if manager is already initialized */
    private var isCreated = false

    private lateinit var readExecutor: ExecutorService // socket read executor
    private lateinit var eventExecutor: ExecutorService // socket event executor

    private lateinit var bluetoothDiscoverableLauncher: ActivityResultLauncher<Intent>
    private lateinit var bluetoothEnableDiscoverableLauncher: ActivityResultLauncher<Intent>
    private lateinit var bluetoothEnableOnlyLauncher: ActivityResultLauncher<Intent>
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>

    /** same variable for clients and hosts. Only used in different modes */
    private var socket: BluetoothSocket? = null
    private var output: BufferedWriter? = null
    private var input: BufferedReader? = null

    /**
     * set to true when a connection is established by connection manager thread. set to false when a connection error occur.
     * This variable is used only to say to the event thread "from now you can/can't accept/try connections".
     */
    private var isConnected = false

    fun registerForActivityResults() {
        // register bluetooth permission handler
        bluetoothPermissionLauncher =
            activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
                this::onBluetoothPermissionsResult
            )
        // register bluetooth discover handler
        bluetoothDiscoverableLauncher =
            activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
                this::onBluetoothDiscoverable
            )

        // register bluetooth enable handler
        bluetoothEnableDiscoverableLauncher =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // check permissions
                if (it.resultCode == RESULT_OK) {
                    Log.i(
                        "WhiteboardBluetoothManager",
                        "Bluetooth enabled. starting discoverable activity"
                    )
                    makeDiscoverable()
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.error)
                        .setMessage(R.string.bluetooth_refused)
                        .show()
                    Log.i(
                        "WhiteboardBluetoothManager",
                        "Bluetooth refused"
                    )
                    onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
                }
            }

        bluetoothEnableOnlyLauncher =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // check permissions
                if (it.resultCode == RESULT_OK) {
                    Log.i(
                        "WhiteboardBluetoothManager",
                        "Bluetooth enabled"
                    )
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.error)
                        .setMessage(R.string.bluetooth_refused)
                        .show()
                    Log.i(
                        "WhiteboardBluetoothManager",
                        "Bluetooth refused"
                    )
                    onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
                }
            }
    }

    fun create() {
        if (!isCreated) {
            readExecutor = Executors.newSingleThreadExecutor()
            eventExecutor = Executors.newSingleThreadExecutor()
            isCreated = true
            Log.i(
                "WhiteboardBluetoothManager",
                "Bluetooth manager executors initialized"
            )
        }
    }

    fun destroy() {
        if (isCreated) {
            submitKillConnection()
            readExecutor.shutdownNow()
            eventExecutor.shutdown()
            isCreated = false
            Log.i(
                "WhiteboardBluetoothManager",
                "Bluetooth manager executors Destroyed"
            )
        }
    }

    fun enableDiscoverable() {
        // ask to enable bluetooth
        bluetoothEnableDiscoverableLauncher.launch(Intent(ACTION_REQUEST_ENABLE))
        Log.i("WhiteboardBluetoothManager", "Bluetooth enable asked for discoverable")
    }

    fun enableBluetooth() {
        bluetoothEnableOnlyLauncher.launch(Intent(ACTION_REQUEST_ENABLE))
        Log.i("WhiteboardBluetoothManager", "Bluetooth enable only asked")
    }

    fun submitKillConnection() {
        eventExecutor.submit {
            if (isConnected) {
                output?.close()
                input?.close()
                socket?.close()
                isConnected = false
                onError?.invoke(ErrorCodes.KILLED)
                Log.i("WhiteboardBluetoothManager", "Bluetooth connection killed")
            }
        }
    }

    /**
     * WARNING: Call only from callbacks!
     */
    fun killConnection() {
        if (isConnected) {
            output?.close()
            input?.close()
            socket?.close()
            isConnected = false
            onError?.invoke(ErrorCodes.KILLED)
        }
        Log.i("WhiteboardBluetoothManager", "Bluetooth connection killed")
    }

    private fun onBluetoothDiscoverable(it: ActivityResult) {
        if (it.resultCode == RESULT_CANCELED) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.error)
                .setMessage(R.string.discoverable)
                .setPositiveButton(R.string.ok) { _, _ -> }
                .show()
            Log.i("WhiteboardBluetoothManager", "Discoverable request refused")
            onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
        } else {
            activity.getState().isSearching = true
            eventExecutor.submit { listenConnection() }
            Log.i(
                "WhiteboardBluetoothManager",
                "Discoverable request accepted. Submitted server task"
            )
        }
    }

    fun makeDiscoverable() {
        // check if bluetooth permissions are granted else ask them
        if (Build.VERSION.SDK_INT >= 31) {
            if (activity.checkSelfPermission(BLUETOOTH_SCAN) != PERMISSION_GRANTED ||
                activity.checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED ||
                activity.checkSelfPermission(ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED
            ) {
                bluetoothPermissionLauncher.launch(
                    arrayOf(
                        BLUETOOTH_SCAN,
                        BLUETOOTH_CONNECT,
                        ACCESS_COARSE_LOCATION
                    )
                )
                Log.i(
                    "WhiteboardBluetoothManager",
                    "Android >=12. Missing bluetooth permissions. Request launched"
                )
                onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
            } else {
                bluetoothDiscoverableLauncher.launch(Intent(ACTION_REQUEST_DISCOVERABLE))
                Log.i(
                    "WhiteboardBluetoothManager",
                    "Android >=12. Bluetooth permissions ok. Discoverable activity launched"
                )
            }
        } else {
            if (activity.checkSelfPermission(ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(arrayOf(ACCESS_COARSE_LOCATION))
                Log.i(
                    "WhiteboardBluetoothManager",
                    "Android <12. Missing bluetooth permissions. Request launched"
                )
                onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
            } else {
                bluetoothDiscoverableLauncher.launch(Intent(ACTION_REQUEST_DISCOVERABLE))
                Log.i(
                    "WhiteboardBluetoothManager",
                    "Android <12. Bluetooth permissions ok. Discoverable activity launched"
                )
            }
        }
    }

    fun registerForDiscovery() {
        // enable discovery
        try {
            activity.registerReceiver(this, IntentFilter(ACTION_FOUND))
            Log.i("WhiteboardBluetoothManager", "Registered for discovery")
        } catch (e: Exception) {
            Log.i("WhiteboardBluetoothManager", "Ignored exception: ${e.javaClass.name}")
        }
    }

    fun unregisterForDiscovery() {
        // disable discovery
        try {
            activity.unregisterReceiver(this)
            Log.i("WhiteboardBluetoothManager", "Unregistered for discovery")
        } catch (e: Exception) {
            Log.i("WhiteboardBluetoothManager", "Ignored exception: ${e.javaClass.name}")
        }
    }

    fun startDiscovery() {
        try {
            val manager = activity.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter.startDiscovery()
            Log.i("WhiteboardBluetoothManager", "Discovery started")
        } catch (e: SecurityException) {
            Log.i("WhiteboardBluetoothManager", "Permissions missing")
        }
    }

    fun stopDiscovery() {
        try {
            val manager = activity.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter.cancelDiscovery()
            Log.i("WhiteboardBluetoothManager", "Discovery stopped")
        } catch (e: SecurityException) {
            Log.i("WhiteboardBluetoothManager", "Permissions missing")
        }
    }

    private fun onBluetoothPermissionsResult(it: MutableMap<String, Boolean>) {
        // if permissions are ok then make device discoverable
        if (Build.VERSION.SDK_INT >= 31) {
            if (it[BLUETOOTH_SCAN]!! && it[BLUETOOTH_CONNECT]!! && it[ACCESS_COARSE_LOCATION]!!) {
                bluetoothDiscoverableLauncher.launch(Intent(ACTION_REQUEST_DISCOVERABLE))
                Log.i(
                    "WhiteboardBluetoothManager",
                    "Android >= 12. Bluetooth permissions granted. discoverable activity launched"
                )
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.title_bluetooth_refused)
                    .setMessage(R.string.bluetooth_permissions_refused)
                    .setPositiveButton(R.string.ok) { _, _ -> }
                    .show()
                Log.i("WhiteboardBluetoothManager", "Android >= 12. Bluetooth permissions refused")
                onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
            }
        } else
            if (it[ACCESS_COARSE_LOCATION]!!) {
                bluetoothDiscoverableLauncher.launch(Intent(ACTION_REQUEST_DISCOVERABLE))
                Log.i(
                    "WhiteboardBluetoothManager",
                    "Android < 12. Bluetooth permissions granted. discoverable activity launched"
                )
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.title_bluetooth_refused)
                    .setMessage(R.string.bluetooth_permissions_refused)
                    .setPositiveButton(R.string.ok) { _, _ -> }
                    .show()
                Log.i("WhiteboardBluetoothManager", "Android < 12. Bluetooth permissions refused")
                onError?.invoke(ErrorCodes.DISCOVERABLE_PERMISSION_DENIED)
            }
    }

    private var onConnect: ((BluetoothDevice) -> Unit)? = null
    private var onError: ((ErrorCodes) -> Unit)? = null
    private var onMessage: ((JSONObject) -> Unit)? = null

    fun setOnConnectionReady(v: (BluetoothDevice) -> Unit) {
        // connection ready callback
        onConnect = v
    }

    fun setOnConnectionError(v: (ErrorCodes) -> Unit) {
        // connection error callback
        onError = v
    }

    fun setOnMessage(v: (JSONObject) -> Unit) {
        // on new message callback
        onMessage = v
    }

    fun submitEvent(f: () -> Unit) {
        eventExecutor.submit { f() }
    }

    fun submitMessage(s: String) {
        // send message
        eventExecutor.submit {
            if (isConnected)
                try {
                    output?.write(s)
                    output?.newLine()
                } catch (e: Exception) {
                    isConnected = false
                    onError?.invoke(ErrorCodes.DISCONNECT)
                }
        }
    }

    fun submitMessageImmediately(s: String) {
        // send message flushing
        eventExecutor.submit {
            if (isConnected)
                try {
                    output?.write(s)
                    output?.newLine()
                    output?.flush()
                } catch (e: Exception) {
                    Log.i("WhiteboardBluetoothManager", "Connection lost with client")
                    isConnected = false
                    onError?.invoke(ErrorCodes.DISCONNECT)
                }
        }
    }

    /**
     * WARNING! Call only from event callback
     */
    fun sendMessage(s: String) {
        if (isConnected)
            try {
                output?.write(s)
                output?.newLine()
            } catch (e: Exception) {
                isConnected = false
                onError?.invoke(ErrorCodes.DISCONNECT)
            }
    }

    /**
     * WARNING! Call only from event callback
     */
    fun sendMessageImmediately(s: String) {
        if (isConnected)
            try {
                output?.write(s)
                output?.newLine()
                output?.flush()
            } catch (e: Exception) {
                Log.i("WhiteboardBluetoothManager", "Connection lost with client")
                isConnected = false
                onError?.invoke(ErrorCodes.DISCONNECT)
            }
    }

    fun submitFlushMessages() {
        // flush pending messages
        eventExecutor.submit {
            if (isConnected)
                try {
                    output?.flush()
                } catch (e: Exception) {
                    Log.i("WhiteboardBluetoothManager", "Connection lost with client")
                    isConnected = false
                    onError?.invoke(ErrorCodes.DISCONNECT)
                }
        }
    }

    private fun listenSocketMessages() {
        try {
            while (true) {
                val tmp = input?.readLine()
                eventExecutor.submit {
                    Log.i("WhiteboardBluetoothManager", "Processing message $tmp")
                    onMessage?.invoke(JSONObject(tmp!!))
                }
            }
        } catch (e: Exception) {
            eventExecutor.submit {
                Log.i("WhiteboardBluetoothManager", "Connection lost with client")
                isConnected = false
                onError?.invoke(ErrorCodes.DISCONNECT)
            }
        }
    }

    private fun listenConnection() {
        try {
            // if a client is already connected ignore next host requests
            if (isConnected)
                return

            Log.i("WhiteboardBluetoothManager", "Listening for connections")
            val manager =
                activity.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val serverSocket = manager.adapter.listenUsingRfcommWithServiceRecord(
                "com.divito.drawtogether.server",
                UUID.fromString(APP_ID)
            )
            // doesn't exists an event for when self discoverable ends, so i'll use a timeout in accept method
            socket = serverSocket.accept(120_000)
            serverSocket.close()
            Log.i("WhiteboardBluetoothManager", "Connection established. Disabling discoverable")

            // create client streams
            output = BufferedWriter(
                OutputStreamWriter(socket!!.outputStream),
                1_000_000
            ) // ~1MB write buffer
            input = BufferedReader(
                InputStreamReader(socket!!.inputStream),
                1_000_000
            ) // ~1MB read buffer
            //invoke on connect callback
            isConnected = true
            onConnect?.invoke(socket!!.remoteDevice)
            Log.i(
                "WhiteboardBluetoothManager",
                "Connected to ${socket!!.remoteDevice.name}"
            )

            readExecutor.submit {
                listenSocketMessages()
                Log.i("WhiteboardBluetoothManager", "Connection with client closed")
            }
        } catch (e: SecurityException) {
            Log.i("WhiteboardBluetoothManager", "Permissions missing")
        } catch (e: IOException) {
            Log.i("WhiteboardBluetoothManager", "Discoverable timeout. Server accept interrupted")
            onError?.invoke(ErrorCodes.TIMEOUT)
        } catch (e: Exception) {
            Log.i("WhiteboardBluetoothManager", "Ignored exception ${e.javaClass.name}")
        }
    }

    private fun tryConnect(dev: BluetoothDevice, name: String) {
        val manager =
            activity.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        try {
            // if client is already connected to a server ignore next connect requests
            if (isConnected)
                return
            // try to connect to device if it connects then setup streams
            Log.i("WhiteboardBluetoothManager", "Trying to connect to device $name")
            socket = dev.createRfcommSocketToServiceRecord(UUID.fromString(APP_ID))
            // cancel discovery before attempting connection as suggested from documentation
            stopDiscovery()
            socket!!.connect()
            // create client streams
            output = BufferedWriter(
                OutputStreamWriter(socket!!.outputStream),
                1_000_000
            ) // ~1MB write buffer
            input = BufferedReader(
                InputStreamReader(socket!!.inputStream),
                1_000_000
            ) // ~1MB read buffer
            Log.i("WhiteboardBluetoothManager", "Connection successful. Connected to device $name")
            // invoke on connect callback
            isConnected = true
            onConnect?.invoke(dev)

            readExecutor.submit {
                listenSocketMessages()
                Log.i("WhiteboardBluetoothManager", "Connection with server closed")
            }
        } catch (e: SecurityException) {
            Log.i("WhiteboardBluetoothManager", "Missing connect permission")
        } catch (e: Exception) {
            // failed to connect restart discovery only if activity is in foreground
            Log.i("WhiteboardBluetoothManager", "Failed to connect device: $name")
            activity.executeIfNotPaused { manager.adapter.startDiscovery() }
            socket?.close()
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            val action: String? = intent?.action
            // listen for device discovered
            if (activity.getSettings().isClient && action == ACTION_FOUND) {
                val dev: BluetoothDevice =
                    intent.getParcelableExtra(EXTRA_DEVICE)!!
                // if device has been found try to connect
                eventExecutor.submit { tryConnect(dev, dev.name) }
                Log.i("WhiteboardBluetoothManager", "Found device: " + dev.name)
            }
        } catch (e: SecurityException) {
            Log.i("WhiteboardBluetoothManager", "Missing permissions")
        } catch (e: Exception) {
            Log.i("WhiteboardBluetoothManager", "Ignored exception: " + e.javaClass.name)
        }
    }
}