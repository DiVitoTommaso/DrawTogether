package com.divito.drawtogether.ui.home

import android.Manifest.permission.*
import android.app.Activity.BLUETOOTH_SERVICE
import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build.VERSION
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.divito.drawtogether.*
import com.divito.drawtogether.databinding.FragmentHomeBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class HomeFragment : Fragment() {

    private lateinit var recycleExecutor: ExecutorService

    private lateinit var projectOpenLauncher: ActivityResultLauncher<Intent>

    private lateinit var bluetoothWhiteboardLauncher: ActivityResultLauncher<Intent>
    private lateinit var bluetoothEnableLauncher: ActivityResultLauncher<Intent>
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var projectView: RecyclerView
    private lateinit var projectList: MutableList<File>

    // used for update project when returning from existing project or from client connection
    private var isRefreshed = true
    private var projectPosition: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        recycleExecutor = Executors.newSingleThreadExecutor()

        // Set refreshed to true to notify if user comes back from a client connection that file list is ALREADY refreshed
        isRefreshed = true
        projectPosition = 0
        // restore state
        if (savedInstanceState != null) {
            projectPosition = savedInstanceState.getInt("position")
            Log.i("HomeFragment", "State restored")
        }

        // get files in projects directory for recycle view.
        projectList =
            mutableListOf(*File(requireActivity().filesDir, "projects").listFiles()!!)
        Log.i("HomeFragment", "Projects list loaded")

        val binding = FragmentHomeBinding.inflate(inflater, container, false)

        with(binding.projectView) {
            // setup layout columns based on device type and orientation
            layoutManager =
                if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                    GridLayoutManager(
                        requireActivity(),
                        if (resources.getBoolean(R.bool.isTablet)) 4 else 2
                    ) else
                    GridLayoutManager(
                        requireActivity(),
                        if (resources.getBoolean(R.bool.isTablet)) 6 else 4
                    )
            Log.i(
                "HomeFragment",
                "Recycle view layout set. IsTablet: ${resources.getBoolean(R.bool.isTablet)}"
            )
            // create adapter for recycle view
            adapter = ProjectAdapter()
            projectView = this
        }
        binding.onlineButton.setOnClickListener { onOnlineClick() }

        bluetoothEnableLauncher =
            registerForActivityResult(StartActivityForResult(), this::onBluetoothEnableResult)
        bluetoothPermissionLauncher =
            registerForActivityResult(RequestMultiplePermissions(), this::onPermissionResult)

        bluetoothWhiteboardLauncher =
            registerForActivityResult(StartActivityForResult(), this::onWhiteboardResult)

        projectOpenLauncher =
            registerForActivityResult(StartActivityForResult(), this::onProjectOpenResult)

        Log.i("HomeFragment", "Home fragment created")
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        projectList.clear()
        recycleExecutor.shutdownNow()
        Log.i("HomeFragment", "Recycle async project loader destroyed")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("position", projectPosition)
        Log.i("HomeFragment", "State saved")
    }

    override fun onPause() {
        super.onPause()
        // set to know fragment is on pause with an old version of the project list
        isRefreshed = false
    }

    private fun onProjectOpenResult(it: ActivityResult) {
        // if user opened a project and returned here then update the project in recycle view
        Log.i("HomeFragment", "Project back stack detected. Project update scheduled")
        projectView.adapter?.notifyItemChanged(projectPosition)
    }

    private fun onWhiteboardResult(it: ActivityResult) {
        val name = it.data!!.extras!!.get("projectName") as String
        // if project name is empty ignore
        if (name != "-landscape.png" && name != "-portrait.png" && name != "-unlocked.png" && !isRefreshed) { // add to recycle view only if fragment has not been recreated
            projectList.add(0, File(requireActivity().filesDir, "projects/$name"))
            projectView.adapter?.notifyItemInserted(0)
        }
    }

    private fun onPermissionResult(it: MutableMap<String, Boolean>) {
        if (VERSION.SDK_INT >= 31) {
            if (it[BLUETOOTH_SCAN]!! && it[BLUETOOTH_CONNECT]!! && it[ACCESS_COARSE_LOCATION]!!) {
                Log.i("HomeFragment", "Android 12 detected. Bluetooth permissions granted")
                startJoinWhiteboard()
            } else {
                AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.title_bluetooth_refused)
                    .setMessage(R.string.bluetooth_permissions_refused)
                    .setPositiveButton(R.string.ok) { _, _ -> }
                    .show()
                Log.i(
                    "HomeFragment", "Android >= 12 detected. Bluetooth permissions refused: " +
                            it[BLUETOOTH_SCAN]!! + " " + it[BLUETOOTH_CONNECT]!! + " " + it[ACCESS_COARSE_LOCATION]!!
                )
            }
        } else
            if (it[ACCESS_COARSE_LOCATION]!!) {
                Log.i("HomeFragment", "Android < 12 detected. Bluetooth permissions granted")
                startJoinWhiteboard()
            } else {
                AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.title_bluetooth_refused)
                    .setMessage(R.string.bluetooth_permissions_refused)
                    .setPositiveButton(R.string.ok) { _, _ -> }
                    .show()
                Log.i(
                    "HomeFragment",
                    "Android >= 12 detected. Bluetooth permissions refused: " + it[ACCESS_COARSE_LOCATION]!!
                )
            }
    }

    private fun onBluetoothEnableResult(it: ActivityResult) {
        if (it.resultCode == RESULT_OK) {
            Log.i("HomeFragment", "Bluetooth enable detected")
            checkPermissions()
        } else {
            AlertDialog.Builder(requireActivity())
                .setTitle(R.string.title_bluetooth_refused)
                .setMessage(R.string.bluetooth_refused)
                .setPositiveButton(R.string.ok) { _, _ -> }
                .show()
            Log.i("HomeFragment", "Bluetooth enable refused")
        }
    }

    private fun onOnlineClick() {
        val activity = requireActivity()
        val manager = activity.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        Log.i("HomeFragment", "Online button clicked")
        if (adapter == null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.title_no_bluetooth)
                .setMessage(R.string.no_bluetooth)
                .setPositiveButton(R.string.ok) { _, _ -> }
                .show()
            Log.i(
                "HomeFragment", "Bluetooth not supported"
            )
            return
        }

        if (!adapter.isEnabled) {
            val i = Intent(ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(i)
            Log.i("HomeFragment", "Bluetooth not enabled. Activity launched")
        } else {
            Log.i("HomeFragment", "Bluetooth already enabled")
            checkPermissions()
        }
    }

    private fun checkPermissions() {
        // check if bluetooth permissions are granted else ask them
        if (VERSION.SDK_INT >= 31) {
            if (requireActivity().checkSelfPermission(BLUETOOTH_SCAN) != PERMISSION_GRANTED
                || requireActivity().checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED ||
                requireActivity().checkSelfPermission(ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED
            ) {
                bluetoothPermissionLauncher.launch(
                    arrayOf(
                        BLUETOOTH_SCAN,
                        BLUETOOTH_CONNECT,
                        ACCESS_COARSE_LOCATION
                    )
                )
                Log.i(
                    "HomeFragment",
                    "Android >= 12. Bluetooth permissions not granted. permissions asked"
                )
            } else {
                Log.i("HomeFragment", "Android >= 12. Bluetooth permissions granted")
                startJoinWhiteboard()
            }
        } else {
            if (requireActivity().checkSelfPermission(ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(arrayOf(ACCESS_COARSE_LOCATION))
                Log.i(
                    "HomeFragment",
                    "Android < 12. Bluetooth permissions not granted. permissions asked"
                )
            } else {
                Log.i(
                    "HomeFragment",
                    "Android < 12. Bluetooth permissions granted"
                )
                startJoinWhiteboard()
            }
        }
    }

    private fun startJoinWhiteboard() {
        bluetoothWhiteboardLauncher.launch(
            Intent(requireActivity(), WhiteboardActivity::class.java)
                .putExtra("isClient", true)
                .putExtra("orientation", ORIENTATION_UNLOCKED)
                .putExtra("projectName", "")
        )

        Log.i(
            "HomeFragment",
            "Whiteboard activity started as client for online sessions"
        )
    }

    /* ------------------------------------------ HOLDER ------------------------------------------ */
    private inner class ProjectHolder(item: View) : RecyclerView.ViewHolder(item) {

        val layout: View

        val card: CardView
        val image: ImageView
        val text: TextView
        val delete: ImageButton
        val rename: ImageButton
        val share: ImageButton

        lateinit var associatedFile: File

        init {
            // save layout views
            layout = item
            card = item.findViewById(R.id.recycle_card)
            image = item.findViewById(R.id.recycle_image)
            text = item.findViewById(R.id.recycle_text)
            delete = item.findViewById(R.id.recycle_delete)
            rename = item.findViewById(R.id.recycle_rename)
            share = item.findViewById(R.id.recycle_share)

            //register open project handler
            image.setOnClickListener { onOpenProject() }
            // register delete handler
            delete.setOnClickListener { onDeleteProject() }
            //register rename handler
            rename.setOnClickListener { onRenameProject() }
            //register share handler
            share.setOnClickListener { onShareProject() }
        }

        private fun onOpenProject() {
            if (!this::associatedFile.isInitialized)
                return
            projectPosition = adapterPosition
            val tmp = associatedFile.name.substringAfterLast("-", "Untitled")
            // start whiteboard activity with project details
            projectOpenLauncher.launch(
                Intent(activity, WhiteboardActivity::class.java)
                    .putExtra("orientation", tmp.substringBeforeLast(".png"))
                    .putExtra("photo", Uri.fromFile(associatedFile).toString())
                    .putExtra("projectName", text.text.toString())
            )

            Log.i("HomeFragment/ImageHolder", "Project $adapterPosition open detected")
        }

        private fun onDeleteProject() {
            if (!this::associatedFile.isInitialized)
                return

            val input = EditText(layout.context)
            input.hint = associatedFile.name.substringBeforeLast("-")
            input.textAlignment = View.TEXT_ALIGNMENT_CENTER
            // show alert to confirm removal
            AlertDialog.Builder(layout.context)
                .setTitle(R.string.title_sure)
                .setMessage(R.string.confirm_removal)
                .setView(input)
                .setPositiveButton(R.string.title_delete) { _, _ ->
                    // check if user has written the project name in the text view
                    if (input.text.toString() == associatedFile.name.substringBeforeLast(
                            "-", "Untitled"
                        )
                    ) {
                        // if user has confirmed removal delete project aka file and notify recycle view
                        associatedFile.delete()
                        projectList.remove(associatedFile)
                        projectView.adapter?.notifyItemRemoved(adapterPosition)
                        Log.i(
                            "HomeFragment/ImageHolder",
                            "Project $adapterPosition confirmed removal"
                        )
                    }
                }.setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            Log.i(
                "HomeFragment/ImageHolder",
                "Project $adapterPosition delete detected. Asked removal"
            )
        }

        private fun onRenameProject() {
            if (!this::associatedFile.isInitialized)
                return

            val input = EditText(layout.context)
            input.textAlignment = View.TEXT_ALIGNMENT_CENTER

            Log.i("HomeFragment/ImageHolder", "Project $adapterPosition rename detected")
            // show alert to ask new name
            AlertDialog.Builder(layout.context)
                .setTitle(R.string.title_rename)
                .setMessage(R.string.project_rename)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val newName = input.text.toString()
                    if (newName.contains("/"))
                        Toast.makeText(
                            layout.context,
                            R.string.invalid_project_name,
                            Toast.LENGTH_LONG
                        ).show()
                    else {
                        val exists =
                            File(associatedFile.parentFile, "$newName-portrait.png").exists()
                                    || File(
                                associatedFile.parentFile,
                                "$newName-landscape.png"
                            ).exists()
                        if (exists)
                            Toast.makeText(
                                layout.context,
                                R.string.title_duplicate,
                                Toast.LENGTH_LONG
                            ).show()
                        else {
                            val newFile = File(
                                associatedFile.parentFile,
                                newName + "-" + associatedFile.name.substringAfterLast("-")
                            )
                            val index = projectList.indexOf(associatedFile)
                            projectList.remove(associatedFile)
                            projectList.add(index, newFile)
                            associatedFile.renameTo(newFile)
                            associatedFile = newFile
                            projectView.adapter?.notifyItemChanged(adapterPosition)
                        }
                    }
                    Log.i(
                        "HomeFragment/ImageHolder",
                        "Project $adapterPosition renamed to ${associatedFile.name}"
                    )

                }.setNegativeButton(R.string.cancel) { _, _ -> }
                .show()
        }

        private fun onShareProject() {
            if (!this::associatedFile.isInitialized)
                return
            // create share intent
            Log.i("HomeFragment/ImageHolder", "Project $adapterPosition share detected")
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "image/png"

            // enable read permissions from file provider
            Log.i("HomeFragment/ImageHolder", "Granted file read access")
            val uri = FileProvider.getUriForFile(
                layout.context,
                "com.divito.drawtogether.provider",
                associatedFile
            )

            // start intent chooser
            Log.i("HomeFragment/ImageHolder", "Share activity launched")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            startActivity(Intent.createChooser(intent, "Share project"))
        }
    }

    /* ------------------------------------------ ADAPTER ------------------------------------------ */
    private inner class ProjectAdapter : RecyclerView.Adapter<ProjectHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectHolder {
            // create project view from xml
            val tmp =
                requireActivity().layoutInflater.inflate(R.layout.recycle_item, parent, false)
            return ProjectHolder(tmp)
        }

        override fun onBindViewHolder(holder: ProjectHolder, position: Int) {
            // change image holder fields based on position
            with(holder) {
                // load scaled bitmap and set to imageview asynchronously
                val tmp  = projectList[position]
                recycleExecutor.submit {
                    Log.i("HomeFragment/ImageAdapter", "Loading project at position: $position")
                    val bm = Bitmap.createScaledBitmap(
                        BitmapFactory.decodeFile(tmp.path),
                        holder.card.measuredWidth,
                        holder.card.measuredHeight,
                        false
                    )


                    requireActivity().runOnUiThread {
                        associatedFile = tmp
                        image.setImageBitmap(bm)
                        text.text = associatedFile.name.substringBeforeLast("-", "Untitled")
                    }
                    Log.i("HomeFragment/ImageAdapter", "Loaded project at position: $position")
                }
            }
        }

        override fun getItemCount() = projectList.size
    }
}