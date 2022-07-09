package com.divito.drawtogether.draw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.divito.drawtogether.DrawMode
import com.divito.drawtogether.R
import com.divito.drawtogether.WhiteboardActivity
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.ramotion.circlemenu.CircleMenuView
import java.io.File
import java.io.IOException

class WhiteboardMenuHandler(private val activity: WhiteboardActivity) :
    CircleMenuView.EventListener() {

    @SuppressLint("InflateParams")
    override fun onButtonClickAnimationStart(view: CircleMenuView, index: Int) {
        super.onButtonClickAnimationStart(view, index)
        when (index) {
            0 -> activity.getState().drawMode = DrawMode.DRAW // change mode to draw mode
            1 -> ColorPickerDialogBuilder.with(activity)
                .density(12)
                .wheelType(ColorPickerView.WHEEL_TYPE.CIRCLE)
                .setPositiveButton(R.string.ok) { _, color, _ ->
                    activity.getState().drawColor = color
                    Log.i("WhiteboardActivity", "Detected color selected: $color")
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .build()
                .show() // ask to choose a color
            2 ->
                // check if project has a name if not then it's an online session => ask user a name
                if (activity.getSettings().projectName == "") {
                    createProject(activity, false)
                } else {
                    Log.i("WhiteboardActivity", "Detected save")
                    activity.scheduleSave() // start project save asynchronously
                }
            3 -> {
                // create alert to choose 'pen'? and 'gum'? size
                val layout = activity.layoutInflater.inflate(R.layout.font_layout, null)
                val draw = layout.findViewById<SeekBar>(R.id.drawBar)
                val erase = layout.findViewById<SeekBar>(R.id.eraseBar)

                // set size to current size
                draw.progress = activity.getState().drawStroke.toInt() * 2
                erase.progress = activity.getState().eraseStroke.toInt() * 2

                // ask user to select the size
                AlertDialog.Builder(activity)
                    .setView(layout)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        // set new size
                        activity.getState().drawStroke = draw.progress.toFloat() / 2
                        activity.getState().eraseStroke = erase.progress.toFloat() / 2
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()

                Log.i("WhiteboardActivity", "Size selected")
            }
            4 -> {
                activity.playMusic()
                Log.i("WhiteboardActivity", "Play music selected")
            }
            5 ->
                // check if project has a name if not then it's an online session => ask user a name
                if (activity.getSettings().projectName == "") {
                    createProject(activity, true)
                } else {
                    Log.i("WhiteboardActivity", "Detected share")
                    activity.scheduleShare() // save project asynchronously and then share it
                }
            6 -> {
                when {
                    activity.getSettings().isClient -> {
                        Log.i(
                            "WhiteboardActivity",
                            "Detected online mode enable while in guest mode"
                        )
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.online_mode)
                            .setMessage(R.string.client_mode)
                            .setPositiveButton(R.string.ok) { _, _ -> }
                            .show()
                    }
                    activity.getState().isSearching -> {
                        Log.i(
                            "WhiteboardActivity",
                            "Detected online mode enable while already searching"
                        )
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.duplicate_search)
                            .setMessage(R.string.already_search)
                            .setPositiveButton(R.string.ok) { _, _ -> }
                            .show()
                    }
                    activity.getState().isConnected -> {
                        Log.i(
                            "WhiteboardActivity",
                            "Detected online mode enable while already connected"
                        )
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.duplicate_connect)
                            .setMessage(R.string.already_connect)
                            .setPositiveButton(R.string.ok) { _, _ -> }
                            .show()
                    }
                    else ->
                        // if battery is low warn user and ask if it want enable bluetooth anyway
                        if (activity.getState().isBatteryLow)
                            AlertDialog.Builder(activity)
                                .setTitle(R.string.warning)
                                .setMessage(R.string.battery_bluetooth)
                                .setPositiveButton(R.string.yes) { _, _ -> askEnableOnline() }
                                .setNegativeButton(R.string.no) { _, _ -> }
                                .show()
                        else
                            // ask to enable online if battery is not low
                            askEnableOnline()
                }
            }
            7 -> activity.getState().drawMode = DrawMode.ERASE
        }
    }

    private fun createProject(activity: WhiteboardActivity, shouldShare: Boolean) {
        val input = EditText(activity)
        input.textAlignment = View.TEXT_ALIGNMENT_CENTER

        Log.i("WhiteboardActivity", "Project assign name detected")
        // show alert to ask new name
        AlertDialog.Builder(activity)
            .setTitle(R.string.title_assign_name)
            .setMessage(R.string.project_name)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString()
                if (name.contains("/"))
                    Toast.makeText(
                        activity,
                        R.string.invalid_project_name,
                        Toast.LENGTH_LONG
                    ).show()
                else {
                    // check if file name is valid or not
                    try {
                        val f = File(activity.filesDir, "temp/${name}")
                        f.createNewFile()
                        f.delete()
                        Log.i("WhiteboardActivity", "File name ok. Whiteboard activity launched")
                    } catch (e: IOException) {
                        Log.i("WhiteboardActivity", "Invalid file name $name")
                        Toast.makeText(activity, R.string.invalid_file_name, Toast.LENGTH_LONG)
                            .show()
                        return@setPositiveButton
                    }

                    // check if file exists
                    val exists =
                        File(activity.filesDir, "projects/$name-portrait.png").exists()
                                || File(
                            activity.filesDir, "projects/$name-landscape.png"
                        ).exists()
                    if (exists)
                        Toast.makeText(
                            activity,
                            R.string.title_duplicate,
                            Toast.LENGTH_LONG
                        ).show()
                    else {
                        activity.getSettings().projectName = name
                        Log.i("WhitebordActivity", "Project name set")
                        if (shouldShare) {
                            Log.i("WhiteboardActivity", "Detected share")
                            activity.scheduleShare()
                        } else {
                            Log.i("WhiteboardActivity", "Detected save")
                            activity.scheduleSave()
                        }
                    }
                }
            }.setNegativeButton(R.string.cancel) { _, _ -> }
            .show()
    }

    private fun askEnableOnline() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.online_mode)
            .setMessage(R.string.online_ask)
            .setPositiveButton(R.string.yes) { _, _ ->
                Log.i("WhiteboardActivity", "Enabling online mode")
                // set device discoverable as server
                val manager =
                    activity.getSystemService(AppCompatActivity.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter
                if (adapter == null) {
                    Log.i("WhiteboardActivity", "Bluetooth not supported")
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.error)
                        .setMessage(R.string.no_bluetooth)
                        .setPositiveButton(R.string.ok) { _, _ -> }
                        .show()
                    return@setPositiveButton
                }

                if (!adapter.isEnabled) {
                    // ask to enable bluetooth
                    Log.i("WhiteboardActivity", "Asking bluetooth enable")
                    activity.getBluetoothManager().enableDiscoverable()
                } else {
                    // check permissions
                    Log.i("WhiteboardActivity", "Asking bluetooth discoverable")
                    activity.getBluetoothManager().makeDiscoverable()
                }

            }
            .setNegativeButton(R.string.no) { _, _ -> }
            .show()
    }
}