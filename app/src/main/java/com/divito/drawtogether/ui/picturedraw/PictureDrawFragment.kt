package com.divito.drawtogether.ui.picturedraw

import android.Manifest.permission.*
import android.app.Activity.*
import android.content.Intent
import android.content.Intent.*
import android.content.pm.PackageManager.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.fragment.app.Fragment
import com.divito.drawtogether.*
import com.divito.drawtogether.databinding.FragmentPictureDrawBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PictureDrawFragment : Fragment() {

    private lateinit var pictureLoaderExecutor: ExecutorService

    private lateinit var binding: FragmentPictureDrawBinding
    private lateinit var pictureLauncher: ActivityResultLauncher<Intent>

    private var imgUri: Uri? = null
    private var isUriReady: Boolean = false
    private var mode: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        pictureLoaderExecutor = Executors.newSingleThreadExecutor()

        binding = FragmentPictureDrawBinding.inflate(inflater, container, false)
        binding.selectPicture.setOnClickListener { onSelectClick() }
        binding.picture.setOnClickListener { showChooserIntent() }

        // register take picture activity result callback
        pictureLauncher =
            registerForActivityResult(StartActivityForResult(), this::onResult)

        // restore state
        if (savedInstanceState != null && savedInstanceState.getBoolean("photoReady")) {
            imgUri = Uri.parse(savedInstanceState["imgUri"] as String)
            mode = savedInstanceState["mode"] as String
            binding.projectName.setText(savedInstanceState["projectName"] as String)
            isUriReady = true
            Log.i("PictureDrawFragment", "State restored")
        }

        // check if camera permission is granted else ask it once per fragment creation
        if (requireActivity().checkSelfPermission(CAMERA) != PERMISSION_GRANTED) {
            Log.i("PictureDrawFragment", "Camera permissions asked")
            val permissionLauncher =
                registerForActivityResult(RequestPermission()) { }
            permissionLauncher.launch(CAMERA)
        }
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("photoReady", isUriReady)
        outState.putString("projectName", binding.projectName.text.toString())
        if (imgUri != null) {
            outState.putString("imgUri", imgUri.toString())
            outState.putString("mode", mode)
        }
        Log.i("PictureDrawFragment", "State saved")
    }

    override fun onStart() {
        super.onStart()
        //reload image on start. Measured width/height are ready when onStart is called
        if (isUriReady) {
            loadFromUri(imgUri!!)
            Log.i("PictureDrawFragment", "Picture reloaded")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.i("PictureDrawFragment", "Background picture load tasks interrupted")
        pictureLoaderExecutor.shutdownNow()
    }

    private fun onResult(it: ActivityResult) {
        try {
            if (it.resultCode == RESULT_OK) {
                // check if it's a file. BETTER! HIGH QUALITY picture
                if (it.data?.data != null) {
                    Log.i("PictureDrawFragment", "Loading picture from Uri")
                    loadFromUri(it.data!!.data!!)
                    binding.selectPicture.setText(R.string.loading)
                    binding.selectPicture.isEnabled = false
                }

                // or if it's a bitmap from camera. NOT GOOD! LOW QUALITY picture, due to intent max data length
                if (it.data?.extras?.get("data") is Bitmap) {
                    Log.i("PictureDrawFragment", "Loading picture from camera bitmap")
                    loadFromBitmap(it.data?.extras?.get("data") as Bitmap)
                    binding.selectPicture.setText(R.string.loading)
                    binding.selectPicture.isEnabled = false
                }

                // notify user that for change picture again he must click it
                makeText(requireActivity(), R.string.picture_change, LENGTH_LONG)
                    .show()
            }
        } catch (e: Exception) {
            makeText(requireActivity(), R.string.try_again, LENGTH_SHORT).show()
        }
    }

    private fun onSelectClick() {
        // handle right action depending if photo is ready or not and project name is set or not
        when {
            !isUriReady -> showChooserIntent()

            binding.projectName.text.isEmpty() ->
                makeText(requireActivity(), R.string.title_missing, LENGTH_LONG).show()

            binding.projectName.text.toString() == "." || binding.projectName.text.toString() == ".." ->
                makeText(activity, R.string.title_duplicate, LENGTH_LONG).show()

            "/" in binding.projectName.text ->
                makeText(activity, R.string.invalid_project_name, LENGTH_LONG).show()

            File(
                requireActivity().filesDir,
                "projects/${binding.projectName.text}-portrait.png"
            ).exists()
                    || File(
                requireActivity().filesDir,
                "projects/${binding.projectName.text}-landscape.png"
            ).exists() ->
                makeText(activity, R.string.title_duplicate, LENGTH_LONG).show()

            else -> {
                // check if file name is valid or not
                try {
                    val f = File(requireActivity().filesDir, "temp/${binding.projectName.text}")
                    f.createNewFile()
                    f.delete()
                    Log.i("PictureDrawFragment", "File name ok. Whiteboard activity launched")
                } catch (e: IOException) {
                    Log.i("PictureDrawFragment", "Invalid file name ${binding.projectName.text}")
                    makeText(activity, R.string.invalid_file_name, LENGTH_LONG).show()
                    return
                }

                // reset activity
                binding.picture.setImageBitmap(
                    BitmapFactory.decodeResource(
                        resources,
                        R.drawable.placeholder
                    )
                )
                isUriReady = false
                binding.selectPicture.setText(R.string.board_config_picture)
                val text = binding.projectName.text.toString()
                binding.projectName.text.clear()
                startActivity(
                    Intent(requireActivity(), WhiteboardActivity::class.java)
                        .putExtra("orientation", mode)
                        .putExtra("photo", imgUri.toString())
                        .putExtra("projectName", text)
                )
            }
        }
    }

    private fun getImageOrientation(bitmap: Bitmap) =
        if (bitmap.height > bitmap.width) ORIENTATION_PORTRAIT else ORIENTATION_LANDSCAPE

    private fun showChooserIntent() {
        // create open camera and browse files intent
        val chooserIntent = createChooser(
            Intent(ACTION_PICK).setType("image/*"),
            "Select Picture"
        )

        // add camera to intent only if permission is granted
        if (requireActivity().checkSelfPermission(CAMERA) == PERMISSION_GRANTED)
            chooserIntent.putExtra(
                EXTRA_INITIAL_INTENTS,
                arrayOf(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            )

        // launch it asking user to select a photo from file or from camera
        pictureLauncher.launch(chooserIntent)
        Log.i("PictureDrawFragment", "Picture chooser launched")
    }

    private fun loadFromBitmap(img: Bitmap) {
        pictureLoaderExecutor.submit {
            // save bitmap to file
            val picture = File(requireActivity().filesDir, "temp images/picture.png")
            val tmp = FileOutputStream(picture)
            img.compress(Bitmap.CompressFormat.PNG, 100, tmp)
            Log.i("PictureDrawFragment", "Bitmap loaded and saved on file")

            mode = getImageOrientation(img)
            // sav uri to file for activity whiteboard
            imgUri = Uri.fromFile(picture)
            isUriReady = true

            val bitmap = getScaledBitmap(img)
            showImage(bitmap)
        }
    }

    private fun loadFromUri(img: Uri) {
        pictureLoaderExecutor.submit {
            // load bitmap from content resolver
            val bm =
                BitmapFactory.decodeStream(requireActivity().contentResolver.openInputStream(img))
            val picture = File(requireActivity().filesDir, "temp images/picture.png")
            val tmp = FileOutputStream(picture)
            // save it as file locally to the app for activity whiteboard
            bm.compress(Bitmap.CompressFormat.PNG, 100, tmp)
            Log.i("PictureDrawFragment", "Picture loaded from Uri and save dto file")

            // set uri to image file
            mode = getImageOrientation(bm)
            imgUri = Uri.fromFile(picture)
            isUriReady = true

            val bitmap = getScaledBitmap(bm)
            showImage(bitmap)
        }
    }

    private fun getScaledBitmap(bm: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(
            bm,
            binding.picture.measuredWidth,
            binding.picture.measuredHeight,
            true
        )
    }

    private fun showImage(bitmap: Bitmap) {
        requireActivity().runOnUiThread {
            Log.i("PictureDrawFragment", "Picture shown")
            binding.picture.setImageBitmap(bitmap)
            binding.selectPicture.setText(R.string.ok)
            binding.selectPicture.isEnabled = true
        }
    }
}