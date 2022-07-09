package com.divito.drawtogether

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.*
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.view.MotionEvent.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.divito.drawtogether.bluetooth.BluetoothManager
import com.divito.drawtogether.bluetooth.BluetoothManager.ErrorCodes
import com.divito.drawtogether.data.WhiteboardSettings
import com.divito.drawtogether.data.WhiteboardState
import com.divito.drawtogether.draw.WhiteboardMenuHandler
import com.divito.drawtogether.draw.WhiteboardView
import com.divito.drawtogether.extensions.*
import com.divito.drawtogether.services.NotificationService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.ramotion.circlemenu.CircleMenuView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.divito.drawtogether.services.*

enum class DrawMode { DRAW, ERASE }

const val USER_NAME = "a"
const val JOIN = "b"
const val ACCEPTED = "c"
const val REJECTED = "d"
const val FROM_X = "e"
const val FROM_Y = "f"
const val TO_X = "g"
const val TO_Y = "h"
const val DRAW_MODE = "i"
const val ERASE = "j"
const val DRAW = "k"
const val DRAW_STROKE = "l"
const val DRAW_COLOR = "m"
const val ERASE_STROKE = "n"
const val SCREEN_WIDTH = "o"
const val SCREEN_HEIGHT = "p"
const val BITMAP = "q"
const val ORIENTATION = "r"


// only two simple GUI components, super easy activity => one line
class WhiteboardActivity : AppCompatActivity() {

    private lateinit var surface: WhiteboardView
    private lateinit var menu: CircleMenuView
    private lateinit var bar: ProgressBar
    private lateinit var searching: TextView

    private lateinit var whiteboardExecutor: ExecutorService
    private lateinit var timeoutExecutor: ExecutorService

    private lateinit var audioLauncher: ActivityResultLauncher<Intent>
    private lateinit var player: MediaPlayer
    private var audioUri: Uri? = null
    private var audioPos: Int = 0

    // sync between UI thread and bluetooth event thread
    private val whiteboardLock = ReentrantLock()

    private val bluetooth = BluetoothManager(this)
    private var settings = WhiteboardSettings()
    private var state = WhiteboardState()

    internal fun getBluetoothManager() = bluetooth
    internal fun getSettings() = settings
    internal fun getState() = state

    // short job (max 3s on modern phones/tablets) service is useless
    internal fun scheduleSave() = whiteboardExecutor.submit { saveProject() }
    internal fun scheduleShare() = whiteboardExecutor.submit { saveProject(); shareProject() }
    private fun scheduleSilentSave() = whiteboardExecutor.submit { save() }

    internal fun playMusic() {
        // if music is already playing ask to stop it
        if (player.isPlaying)
            AlertDialog.Builder(this)
                .setTitle(R.string.already_playing)
                .setMessage(R.string.player)
                .setPositiveButton(R.string.yes) { _, _ ->
                    try {
                        // try to stop it
                        if (player.isPlaying)
                            player.stop()
                        else
                        // ask to pick an audio
                            playAudio()
                    } catch (e: Exception) {
                    }
                }
                .setNegativeButton(R.string.no) { _, _ -> }
                .show()
        else
        // else ask to pick ana audio
            playAudio()
    }

    private fun scheduleTimeout() {
        // if application is not killed then wait 30s then post a notification and kill connection
        try {
            Log.i("WhiteboardActivity", "App background timeout started")
            Thread.sleep(30_000)
            Log.i("WhiteboardActivity", "App background timeout ended")
            // if still connected kill connection
            whiteboardLock.withLock {
                if (state.isConnected)
                    bluetooth.submitKillConnection()
            }
        } catch (e: Exception) {
        }

        // if user return to activity (task interrupted) or timeout end then clear notification.
        val notify = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notify.cancel(1)
        // stop foreground service
        runOnUiThread { stopService(Intent(this, NotificationService::class.java)) }
    }

    internal fun executeIfNotPaused(f: () -> Unit) {
        whiteboardLock.withLock {
            if (!isPaused)
                f.invoke()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whiteboard)

        supportActionBar?.hide()
        setFullscreen()
        settings = WhiteboardSettings()
        state = WhiteboardState()

        timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        whiteboardExecutor = Executors.newSingleThreadExecutor()
        Log.i("WhiteboardActivity", "Whiteboard created")

        // register whiteboards event handlers
        bar = findViewById(R.id.bar)
        searching = findViewById(R.id.searching)
        surface = findViewById(R.id.surface)
        menu = findViewById(R.id.whiteboardMenu)
        menu.eventListener = WhiteboardMenuHandler(this)
        Log.i("WhiteboardActivity", "Menu handler registered")
        // init bluetooth manager
        bluetooth.registerForActivityResults()
        bluetooth.create()
        Log.i("WhiteboardActivity", "bluetooth manager initialized")

        // register touch handlers
        surface.setOnTouchListener(this::onTouchMove)
        surface.setOnSurfaceReady(this::setupWhiteboard)
        menu.getMenuButton()?.setOnLongClickListener(this::onMenuLongClick)
        menu.getMenuButton()?.setOnDragListener(this::onMenuDrag)
        // get user preferences from settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        settings.exitSave = prefs.getBoolean("exit_save", true)
        settings.askJoin = prefs.getBoolean("ask_join", true)
        settings.batterySave = prefs.getBoolean("battery_save", true)
        settings.loop = prefs.getBoolean("player_loop", true)
        Log.i("WhiteboardActivity", "User preferences loaded")
        // load or reload whiteboard

        if (intent.extras != null)
            loadWhiteboardSettings(intent.extras!!)
        if (savedInstanceState != null)
            reloadWhiteboardState(savedInstanceState)

        // create the media player
        audioLauncher = registerForActivityResult(StartActivityForResult(), this::onAudioResult)
        player = MediaPlayer()
        player.isLooping = settings.loop

        // if it's restored from a state uri is loaded then load it in the media player
        try {
            if (audioUri != null) {
                player.setDataSource(this, audioUri!!)
                initPlayer()
            }
        } catch (e: Exception) {
            Log.i("WhiteboardActivity", "Not a valid uri")
        }

        prepareBluetoothManager()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("WhiteboardActivity", "executors destroyed")
        // shut down executors
        whiteboardExecutor.shutdownNow()
        timeoutExecutor.shutdownNow()

        player.release()
        if (isFinishing) {
            // if activity is finishing then destroy bluetooth
            bluetooth.destroy()
            Log.i("WhiteboardActivity", "Bluetooth manager destroyed")
        }
    }

    private var isPaused = false

    override fun onPause() {
        super.onPause()
        // unregister bluetooth state change and battery low if activity goes in background
        try {
            unregisterReceiver(receiver)
            whiteboardLock.withLock {
                // unregister bluetooth discovery only if user is in client mode
                if (settings.isClient && !state.isConnected) {
                    Log.i(
                        "WhiteboardActivity", "Client session detected. Pausing bluetooth discovery"
                    )

                    bluetooth.unregisterForDiscovery()
                    bluetooth.stopDiscovery()
                }
                // mark activity as paused for other threads
                isPaused = true

                if (!isFinishing) {
                    // if activity is not finishing and some one is connected start the notification service
                    if (state.isConnected)
                        startForegroundService(
                            Intent(this, NotificationService::class.java).putExtra("other", other)
                        )
                    // if activity goes in background and exit save is true then schedule an auto save
                    if (settings.projectName != "" && settings.exitSave)
                        scheduleSilentSave()

                    //if user exit from activity schedule a connection kill if connected
                    killTask = timeoutExecutor.submit(this::scheduleTimeout)
                    // pause media player if was running
                    player.pause()
                }
            }
        } catch (e: Exception) {
            Log.i("WhiteboardActivity", "Pause error: ${e.javaClass.name}")
        }
    }

    private var killTask: Future<*>? = null

    override fun onResume() {
        super.onResume()
        try {
            // listen for bluetooth state change and battery low
            with(IntentFilter()) {
                addAction(ACTION_BATTERY_LOW)
                addAction(ACTION_BATTERY_OKAY)
                addAction(ACTION_STATE_CHANGED)
                registerReceiver(receiver, this)
            }
            whiteboardLock.withLock {
                // register for bluetooth search only if user is in client mode and not connected
                if (settings.isClient && !state.isConnected) {
                    Log.i(
                        "WhiteboardActivity",
                        "Client session detected. Resuming bluetooth pending discovery"
                    )
                    // if is in client mode enable discovery
                    bluetooth.registerForDiscovery()
                    bluetooth.startDiscovery()
                    disableUI(R.string.searching)
                }
                // mark activity as not paused for other threads
                isPaused = false
                stopService(Intent(this, NotificationService::class.java))
                // cancel eventual kill connection task and restart media player if was running
                whiteboardLock.withLock { killTask?.cancel(true) }
                player.start()
            }
        } catch (e: Exception) {
            Log.i("WhiteboardActivity", "Resume error: ${e.javaClass.name}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // save state
        super.onSaveInstanceState(outState)
        outState.putString("photo", settings.pictureUri.toString())
        outState.putString("projectName", settings.projectName)
        outState.putString("orientation", settings.orientation)
        outState.putBoolean("isClient", settings.isClient)
        outState.putFloat("stroke", state.drawStroke)
        outState.putInt("color", state.drawColor)
        outState.putFloat("eraseRadius", state.eraseStroke)
        outState.putString("audio", audioUri.toString())
        outState.putInt("position", player.currentPosition)
        Log.i("WhiteboardActivity", "State saved")
    }

    override fun onBackPressed() {
        Log.i("WhiteboardActivity", "Back press detected")
        // save whiteboard status asynchronously and exit if auto save is enabled and has a name
        whiteboardExecutor.submit {
            if (settings.exitSave && settings.projectName != "") {
                Log.i("WhiteboardActivity", "exit detected with auto save enabled. Saving project")
                // save project and exit (only after save completed)
                saveProject()
                runOnUiThread {
                    setResult(
                        0,
                        Intent().putExtra(
                            "projectName",
                            "${settings.projectName}-${settings.orientation}.png"
                        )
                    )
                    finish()
                }
            } else {
                // else exit without doing nothing extra, waiting for pending save being a single threaded executor
                runOnUiThread {
                    setResult(
                        0,
                        Intent().putExtra(
                            "projectName",
                            "${settings.projectName}-${settings.orientation}.png"
                        )
                    )
                    finish()
                }
            }
        }
    }

    private fun prepareBluetoothManager() {
        // prepare bluetooth callbacks
        bluetooth.setOnConnectionReady { onBluetoothConnect(it) }
        bluetooth.setOnConnectionError { onBluetoothError(it) }
        bluetooth.setOnMessage { onBluetoothMessage(it) }
    }

    private fun onBluetoothConnect(it: BluetoothDevice) {
        Log.i("WhiteboardActivity", "Connection ready with device $it")

        // update vars on connection established and disable discovery
        whiteboardLock.withLock {
            state.isConnected = true
            state.isSearching = false

            // if terminal connect while paused in background then schedule a kill
            if (isPaused)
                killTask = timeoutExecutor.submit(this::scheduleTimeout)

            // stop discovery a client has connected
            if (settings.isClient)
                bluetooth.stopDiscovery()
        }

        // send name and project configurations to other terminal
        val info = JSONObject()
        val name = GoogleSignIn.getLastSignedInAccount(this)!!.displayName
        info.put(USER_NAME, name).put(ORIENTATION, requestedOrientation)
        bluetooth.sendMessageImmediately(info.toString())
        Log.i("WhiteboardActivity", "Device info and name sent")

        runOnUiThread {
            // if client clear surface and get ready to join protocol
            if (settings.isClient) {
                disableUI(R.string.waiting)
                surface.clearSurface()
            } else {
                // if it's host move actual drawn to the under layers and start join protocol
                // so users that enter now can draw on 'host drawn state'
                disableUI(R.string.waiting)
                surface.moveLayer(0, 2)
            }
        }
    }

    private fun disableUI(id: Int) {
        // disable UI
        surface.isEnabled = false
        menu.close(false)
        menu.getMenuButton()?.isEnabled = false
        bar.visibility = View.VISIBLE
        searching.setText(id)
        searching.visibility = View.VISIBLE
    }

    private fun enableUI() {
        // enable UI
        surface.isEnabled = true
        menu.getMenuButton()?.isEnabled = true
        bar.visibility = View.INVISIBLE
        searching.visibility = View.INVISIBLE
    }

    private fun onBluetoothError(it: ErrorCodes) {
        Log.i("WhiteboardActivity", "Online error: $it")
        runOnUiThread {
            when (it) {
                ErrorCodes.TIMEOUT -> {
                    // if discovery found no device as host then notify user to try again
                    AlertDialog.Builder(this).setTitle(R.string.no_found)
                        .setMessage(R.string.no_device)
                        .setPositiveButton(R.string.ok) { _, _ -> }
                        .show()
                    whiteboardLock.withLock {
                        state.isConnected = false
                        state.isSearching = false
                    }
                }
                ErrorCodes.DISCONNECT, ErrorCodes.KILLED -> {
                    // if terminal disconnected then reset vars
                    whiteboardLock.withLock {
                        state.isConnected = false
                        state.isSearching = false

                        // cancel pending kill task, terminal already disconnected
                        killTask?.cancel(true)
                        // if it's client and activity is in foreground then restart discovery
                        if (settings.isClient) {
                            if (!isPaused)
                                bluetooth.startDiscovery()

                            disableUI(R.string.searching)
                            if (!isPaused)
                                makeText(this, "Host $other disconnected", LENGTH_SHORT).show()
                            else
                                postNotification(this, 2, "Host $other disconnected")
                        } else {
                            enableUI()
                            if (!isPaused)
                                makeText(this, "Guest $other disconnected", LENGTH_SHORT).show()
                            else
                                postNotification(this, 2, "Guest $other disconnected")
                        }
                    }
                }
                else -> {}
            }

            // if client disconnect then move partial results to permanent result layer
            if (!settings.isClient) {
                Log.i("WhiteboardActivity", "Client disconnected. Saving results")
                disableUI(R.string.saving)
                surface.moveLayer(1, 2)
                surface.moveLayer(0, 2)
                enableUI()
            }
        }
    }

    private fun getScalingFactor(fromSize: Int, toSize: Int) =
        toSize.toFloat() / fromSize.toFloat()

    private var other = ""
    private var clientDrawStyle = Paint()
    private var clientEraseStroke = 0.0.toFloat()
    private var bitmap: Bitmap? = null

    private fun onBluetoothMessage(it: JSONObject) {
        Log.i("WhiteboardActivity", "Online received: $it")

        when {
            it.has(DRAW_MODE) -> {
                // get scaling factor from other terminal display metrics
                val scaleX =
                    getScalingFactor(it.getInt(SCREEN_WIDTH), surface.getCurrentSize().first)
                val scaleY =
                    getScalingFactor(it.getInt(SCREEN_HEIGHT), surface.getCurrentSize().second)
                // process draw event
                if (it[DRAW_MODE] == DRAW) {
                    clientDrawStyle.color = it.getInt(DRAW_COLOR)
                    clientDrawStyle.strokeWidth = it.getDouble(DRAW_STROKE).toFloat()

                    // draw line: priority 0 => host, priority 1 => client
                    runOnUiThread {
                        surface.drawLine(
                            (it.getDouble(FROM_X) * scaleX).toFloat(),
                            (it.getDouble(FROM_Y) * scaleY).toFloat(),
                            (it.getDouble(TO_X) * scaleX).toFloat(),
                            (it.getDouble(TO_Y) * scaleY).toFloat(),
                            clientDrawStyle,
                            if (settings.isClient) 0 else 1,
                            2
                        )
                        Log.i("WhiteboardActivity", "Draw line request processed")
                    }
                } else {
                    // erase line: priority 0 => host, priority 1 => client
                    clientEraseStroke =
                        it.getDouble(ERASE_STROKE).toFloat()
                    runOnUiThread {
                        surface.clearLine(
                            (it.getDouble(FROM_X) * scaleX).toFloat(),
                            (it.getDouble(FROM_Y) * scaleY).toFloat(),
                            (it.getDouble(TO_X) * scaleX).toFloat(),
                            (it.getDouble(TO_Y) * scaleY).toFloat(),
                            clientEraseStroke,
                            if (settings.isClient) 0 else 1,
                            2
                        )
                        Log.i(
                            "WhiteboardActivity",
                            "erase request processed"
                        )
                    }
                }
            }
            // host terminal board state
            it.has(BITMAP) -> {
                // if message contains a bitmap then it's the surface state. Take it and load it on client surface
                // saving into a bitmap so if bitmap is received before activity restart due to orientation change
                // when whiteboard will be ready will be drawn automatically
                bitmap =
                    (it[BITMAP] as String).toBitmap()
                val scaled = Bitmap.createScaledBitmap(
                    bitmap!!,
                    surface.getCurrentSize().first,
                    surface.getCurrentSize().second,
                    true
                )
                runOnUiThread {
                    surface.drawPicture(scaled, 2)
                    enableUI()
                }
            }
            // client has been accept
            it.has(JOIN) && settings.isClient -> {
                // if client was accepted then notify join successful
                if (it[JOIN] == ACCEPTED) {
                    runOnUiThread {
                        searching.setText(R.string.connecting)
                        whiteboardLock.withLock {
                            if (!isPaused)
                                makeText(
                                    this@WhiteboardActivity,
                                    "You joined $other's whiteboard",
                                    LENGTH_SHORT
                                ).show()
                            else
                                postNotification(this, 3, "You joined $other's whiteboard")
                        }
                    }
                    Log.i(
                        "WhiteboardActivity",
                        "Request ACCEPTED"
                    )
                } else {
                    // if client was reject then notify user about refuse
                    runOnUiThread {
                        whiteboardLock.withLock {
                            if (!isPaused)
                                makeText(
                                    this@WhiteboardActivity,
                                    "You have been reject by ${it[USER_NAME]}",
                                    LENGTH_SHORT
                                ).show()
                            else
                                postNotification(
                                    this,
                                    3,
                                    "You have been reject by ${it[USER_NAME]}"
                                )
                        }
                    }
                    bluetooth.submitKillConnection()
                    Log.i("WhiteboardActivity", "Request REJECTED")
                }
            }
            // client received server name
            it.has(USER_NAME) && settings.isClient -> {
                other =
                    it[USER_NAME] as String

                // if it's connection established message then get first informations about host terminal
                runOnUiThread {
                    requestedOrientation =
                        it[ORIENTATION] as Int
                    if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                        settings.orientation = ORIENTATION_PORTRAIT
                    else
                        settings.orientation = ORIENTATION_LANDSCAPE
                    searching.setText(R.string.waiting)
                }
                Log.i(
                    "WhiteboardActivity",
                    "Host username received: ${it[USER_NAME]}"
                )
            }
            // host whiteboard received client name
            it.has(USER_NAME) && !settings.isClient -> {
                other = it[USER_NAME] as String

                runOnUiThread {
                    // if ask join is enabled ask user to accept the guest
                    if (settings.askJoin) {
                        AlertDialog.Builder(
                            this
                        )
                            .setTitle("Join request")
                            .setMessage("${it[USER_NAME]} wants to join")
                            .setPositiveButton("Accept") { _, _ ->
                                whiteboardLock.withLock {
                                    if (!isPaused)
                                        makeText(
                                            this,
                                            "${it[USER_NAME]} has joined",
                                            LENGTH_LONG
                                        ).show()
                                    else
                                        postNotification(this, 3, "${it[USER_NAME]} has joined")
                                }
                                // on accept click start join protocol
                                bluetooth.submitEvent {
                                    runOnUiThread {
                                        disableUI(R.string.connecting)
                                    }

                                    acceptClient()

                                    runOnUiThread { enableUI() }
                                }
                                Log.i("WhiteboardActivity", "Client ACCEPTED")
                            }
                            .setNegativeButton("Reject") { _, _ ->
                                // on reject click refuse connection sending a message and then killing the socket
                                bluetooth.submitEvent {
                                    val json =
                                        JSONObject()
                                            .put(JOIN, REJECTED)
                                            .put(
                                                USER_NAME,
                                                GoogleSignIn.getLastSignedInAccount(
                                                    this
                                                )!!.displayName
                                            )
                                    bluetooth.sendMessageImmediately(json.toString())
                                    bluetooth.killConnection()
                                }
                                Log.i("WhiteboardActivity", "Client REJECTED")
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        // if ask join is disabled then accept automatically and start join protocol
                        bluetooth.submitEvent {
                            runOnUiThread {
                                disableUI(R.string.connecting)
                            }

                            acceptClient()

                            runOnUiThread { enableUI() }
                        }
                        Log.i("WhiteboardActivity", "Client auto accepted")
                    }
                }
                Log.i("WhiteboardActivity", "Client connected")
            }
        }
    }

    private fun acceptClient() {
        // send an accept message
        val json = JSONObject().put(JOIN, ACCEPTED).put(
            USER_NAME,
            GoogleSignIn.getLastSignedInAccount(
                this
            )!!.displayName
        )
        bluetooth.sendMessageImmediately(json.toString())

        json.clear()

        // then send the surface state
        json.put(BITMAP, surface.drawToBitmap().toBase64String())
        bluetooth.sendMessageImmediately(json.toString())
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun reloadWhiteboardState(params: Bundle) {
        // reload state photo (last save)
        settings.pictureUri = if (params.getString("photo") != null)
            Uri.parse(params.getString("photo"))
        else
            null

        // lock orientation whiteboard. orientation changes have no sense in a draw application. Other apps support it with a bad solution
        if (params["orientation"] == ORIENTATION_PORTRAIT)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (params["orientation"] == ORIENTATION_LANDSCAPE)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (params["orientation"] == ORIENTATION_UNLOCKED)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // reload state
        settings.projectName = params["projectName"]!! as String
        settings.orientation = params["orientation"]!! as String
        settings.isClient = params["isClient"] as Boolean
        state.drawColor = params["color"] as Int
        state.drawStroke = params["stroke"] as Float
        state.eraseStroke = params["eraseRadius"] as Float
        if (params["audio"] != null) {
            audioUri = Uri.parse(params["audio"] as String)
            audioPos = params["position"] as Int
        }
        Log.i("WhiteboardActivity", "State and configuration restored")
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun loadWhiteboardSettings(params: Bundle) {
        // lock orientation whiteboard. orientation changes have no sense since resolution change on phones and tablets.
        if (params["orientation"] == ORIENTATION_PORTRAIT)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (params["orientation"] == ORIENTATION_LANDSCAPE)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (params["orientation"] == ORIENTATION_UNLOCKED)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // load state from intent extra
        settings.orientation = params["orientation"]!! as String
        settings.projectName = params["projectName"]!! as String
        settings.isClient = params["isClient"] as? Boolean ?: false
        state.drawColor = Color.BLACK
        state.drawStroke = 10.0.toFloat()
        state.eraseStroke = 10.0.toFloat()

        if (params["photo"] != null) {
            // get local copy of picture and move it to right location and set it in settings
            val file = Uri.parse(params.getString("photo")).toFile()
            val newFile =
                File(filesDir, "projects/${settings.projectName}-${settings.orientation}.png")
            file.renameTo(newFile)
            settings.pictureUri = Uri.fromFile(newFile)
        }

        Log.i("WhiteboardActivity", "Configuration loaded")
    }

    private val drawStyle = Paint()
    private val json = JSONObject()

    private var oldX: Float = 0.0.toFloat()
    private var oldY: Float = 0.0.toFloat()

    private var firstMotion: Boolean = true
    private var canDraw: Boolean = true

    private fun onTouchMove(v: View, event: MotionEvent): Boolean {
        // handle touch drag gesture
        when (event.action) {
            ACTION_DOWN -> {
                // close menu and set as first motion
                menu.close(false)
                firstMotion = true
            }
            ACTION_UP, ACTION_CANCEL -> {
                // restart motion event handling
                canDraw = true
                // send all draw events if client is connected
                if (state.isConnected)
                    bluetooth.submitFlushMessages()
            }
            ACTION_MOVE -> {
                // ignore draw event if user is moving menu
                if (event.x < 20 || event.x > (surface.getCurrentSize().first - 20)
                    || event.y < 20 || event.y > (surface.getCurrentSize().second - 20)
                )
                    canDraw = false

                // if surface cannot be drawn update and return
                if (!canDraw)
                    return true

                // if first touch move update oldX and oldY only
                if (firstMotion) {
                    oldX = event.x
                    oldY = event.y
                    firstMotion = false
                    return true
                }

                // - support orientation change. Each JSON Object contains ~50 bytes of message even
                // without screen information situation doesn't change. We still have ~40 bytes per message
                // with handling ratio of 60 => 3000 bytes per second. too many!
                // with handling ratio of 20 => 1000 bytes per second. much better!
                if (state.isConnected)
                    json.put(FROM_X, oldX)
                        .put(FROM_Y, oldY)
                        .put(TO_X, event.x)
                        .put(TO_Y, event.y)
                        .put(SCREEN_WIDTH, surface.getCurrentSize().first)
                        .put(SCREEN_HEIGHT, surface.getCurrentSize().second)

                if (state.drawMode == DrawMode.ERASE) {
                    // clear line at the right layers
                    val stroke = state.eraseStroke.toPixels(this)
                    surface.clearLine(
                        oldX,
                        oldY,
                        event.x,
                        event.y,
                        state.eraseStroke,
                        if (settings.isClient) 1 else 0,
                        2
                    )
                    // send erase event
                    if (state.isConnected)
                        json.put(DRAW_MODE, ERASE)
                            .put(ERASE_STROKE, state.eraseStroke)
                }

                if (state.drawMode == DrawMode.DRAW) {
                    val stroke = state.drawStroke.toPixels(this)

                    // send draw event
                    if (state.isConnected)
                        json.put(DRAW_MODE, DRAW)
                            .put(DRAW_COLOR, state.drawColor)
                            .put(DRAW_STROKE, state.drawStroke.toPixels(this))

                    drawStyle.color = state.drawColor
                    drawStyle.strokeWidth = stroke

                    // draw line on the right layer
                    surface.drawLine(
                        oldX,
                        oldY,
                        event.x,
                        event.y,
                        drawStyle,
                        if (settings.isClient) 1 else 0,
                        2
                    )
                }

                /*
                 * if battery save is enabled then buffer draw events and send all together to avoid many
                 * small data write on socket making a single one when user release the touch screen or buffer
                 * full this implies other terminal will see the 'draw events' with very large delay. To
                 *  avoid this if save battery is turned off and battery is not low every event will trigger
                 * a data send making whiteboard more reactive but at battery cost of no buffers use.
                */
                if (state.isConnected)
                    if (settings.batterySave)
                        bluetooth.submitMessage(json.toString()) // good for battery
                    else
                        bluetooth.submitMessageImmediately(json.toString()) // bad for battery

                // clear json to avoid reallocating new object every motion event
                json.clear()

                oldX = event.x
                oldY = event.y
            }
        }
        return true
    }

    private fun onMenuLongClick(v: View): Boolean {
        // on menu long click prepare moving and disable it
        menu.close(false)
        menu.startDragAndDrop(null, View.DragShadowBuilder(v), v, 0)
        Log.i("WhiteboardActivity", "Menu start drag detected")
        return false
    }

    private fun onMenuDrag(v: View, event: DragEvent): Boolean {
        // hide menu
        if (event.action == DragEvent.ACTION_DRAG_STARTED)
            menu.visibility = View.INVISIBLE

        // move menu to drop location and show it (found no solution to drop shadow animation) -.-'
        if (event.action == DragEvent.ACTION_DRAG_ENDED) {
            menu.x = event.x - menu.width / 2
            menu.y = event.y - menu.height / 2
            menu.visibility = View.VISIBLE
            Log.i("WhiteboardActivity", "Menu stop drag detected")
        }
        return true
    }

    private fun setupWhiteboard() {
        Log.i("WhiteboardActivity", "Whiteboard surface ready")
        // draw background
        surface.drawBackground(3)

        whiteboardExecutor.submit {
            try {
                // if picture != null then load picture as background
                if (settings.pictureUri != null) {
                    val tmp = Bitmap.createScaledBitmap(
                        BitmapFactory.decodeFile(settings.pictureUri!!.path!!),
                        surface.getCurrentSize().first,
                        surface.getCurrentSize().second,
                        true
                    )
                    Log.i("WhiteboardActivity", "Drawing (picture/old state) on surface")
                    runOnUiThread { surface.drawPicture(tmp, 2) }
                }

                if (bitmap != null) {
                    val tmp = Bitmap.createScaledBitmap(
                        bitmap!!, surface.getCurrentSize().first,
                        surface.getCurrentSize().second, true
                    )
                    Log.i("WhiteboardActivity", "Drawing bitmap on surface")
                    runOnUiThread { surface.drawPicture(tmp, 2) }
                }
                // set whiteboard as fully loaded
                runOnUiThread { state.isWhiteboardLoaded = true }
                Log.i("WhiteboardActivity", "Whiteboard loaded")
            } catch (e: Exception) {
                Log.i(
                    "WhiteboardActivity",
                    "Ignored exception ${e.javaClass.name}"
                )
            }
        }
    }

    private fun save() {
        // wait whiteboard load complete
        if (!state.isWhiteboardLoaded)
            return

        Log.i("WhiteboardActivity", "Saving project")
        // write file in a backup file saving inside file name orientation then substitute real one
        val backup =
            File(
                filesDir,
                "temp/${settings.projectName}-${settings.orientation}.png"
            )
        val project =
            File(
                filesDir,
                "projects/${settings.projectName}-${settings.orientation}.png"
            )

        backup.delete()
        backup.createNewFile()
        surface.writeToFile(backup)
        backup.renameTo(project)
        settings.pictureUri = Uri.fromFile(project)

        Log.i("WhiteboardActivity", "Project saved")
    }

    private fun saveProject() {
        runOnUiThread {
            // show an info about save in progress
            surface.isEnabled = false
            menu.close(false)
            menu.getMenuButton()?.isEnabled = false
            makeText(this, R.string.saving, LENGTH_SHORT).show()
        }

        save() // save

        runOnUiThread {
            // show an info about save completed
            surface.isEnabled = true
            menu.close(false)
            menu.getMenuButton()?.isEnabled = true
            makeText(this, R.string.saved, LENGTH_SHORT).show()
        }
    }

    private fun shareProject() {
        // create intent and share project
        Log.i("WhiteboardActivity", "Share project detected")
        runOnUiThread {
            try {
                val intent = Intent(ACTION_SEND)
                intent.type = "image/png"

                Log.i("WhiteboardActivity", "File read permissions granted")
                val uri = FileProvider.getUriForFile(
                    this,
                    "com.divito.drawtogether.provider",
                    File(
                        filesDir,
                        "projects/${settings.projectName}-${settings.orientation}.png"
                    )
                )

                intent.addFlags(FLAG_GRANT_READ_URI_PERMISSION)
                intent.putExtra(EXTRA_STREAM, uri)
                startActivity(createChooser(intent, "Share project"))
                Log.i("WhiteboardActivity", "Share activity launched")
            } catch (e: Exception) {
                makeText(this, R.string.no_activity, LENGTH_SHORT).show()
                Log.i("WhiteboardActivity", "No activity found")
            }
        }
    }

    private fun onAudioResult(it: ActivityResult) {
        // check if result has an uri
        try {
            if (it.data?.data != null) {
                try {
                    Log.i("WhiteboardActivity", "Audio from activity result got")
                    player.stop()
                } catch (e: Exception) {
                    Log.i("WhiteboardActivity", "MediaPlayer still not initialized")
                }
                // if yes then initialize the media player and save it in the activity state
                audioUri = it.data!!.data!!
                player.setDataSource(this, it.data!!.data!!)
                initPlayer()
            }
        } catch (e: Exception) {
            // data source error
        }
    }

    private fun initPlayer() {
        // set the on prepared listener to start the media player on ready
        player.setOnPreparedListener {
            whiteboardLock.withLock {
                Log.i("WhiteboardActivity", "MediaPlayer ready")
                // start it only if activity is in foreground
                if (!isPaused) {
                    Log.i("WhiteboardActivity", "MediaPlayer started")
                    player.start()
                }
            }
        }

        // listen for errors
        player.setOnErrorListener { _, what, _ ->
            // if errors are related to IO errors then notify user this is a bad file
            if (what == MediaPlayer.MEDIA_ERROR_IO || what == MediaPlayer.MEDIA_ERROR_MALFORMED)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error)
                        .setMessage(R.string.no_audio)
                        .setPositiveButton(R.string.ok) { _, _ -> }
                        .show()
                }
            Log.i("WhiteboardActivity", "MediaPlayer got an error. Error code: $what")
            true
        }
        // prepare the song asynchronously
        player.prepareAsync()
        Log.i("WhiteboardActivity", "MediaPlayer initialized")
    }

    private fun playAudio() {
        if (state.isBatteryLow)
            AlertDialog.Builder(this)
                .setTitle(R.string.warning)
                .setMessage(R.string.battery)
                .setPositiveButton(R.string.yes) { _, _ -> play() }
                .setNegativeButton(R.string.no) { _, _ -> }
                .show()
        else
            play()
    }

    private fun play() {
        try {
            Log.i("WhiteboardActivity", "Audio pick activity launched")
            val intent =
                createChooser(
                    Intent(
                        ACTION_PICK,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    ), "Select an audio file"
                )
            // ask use to pick ana audio
            audioLauncher.launch(intent)
        } catch (e: Exception) {
            makeText(this, R.string.no_activity, LENGTH_SHORT).show()
            Log.i("WhiteboardActivity", "No activity found")
        }
    }

    private fun setFullscreen() {
        // enable full screen (doesn't work on S9 -> Android 10. Works on Tab S7 -> Android 12)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, findViewById(R.id.root)).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            // ask to re-enable bluetooth on bluetooth turned off
            if (action == ACTION_STATE_CHANGED
                && intent.getIntExtra(EXTRA_STATE, -1) == STATE_OFF
                && (settings.isClient || state.isSearching)
            ) {
                Log.i("WhiteboardActivity", "Bluetooth has been turned off")
                bluetooth.enableBluetooth()
            }

            // disable forced battery save if battery is okay
            if (action == ACTION_BATTERY_OKAY)
                try {
                    Log.i("WhiteboardActivity", "Battery is okay")
                    state.isBatteryLow = false
                } catch (e: Exception) {
                }
            // enable force battery save on battery low
            if (action == ACTION_BATTERY_LOW)
                try {
                    Log.i("WhiteboardActivity", "Battery is low")
                    state.isBatteryLow = true
                    player.pause()
                } catch (e: Exception) {
                }
        }
    }
}