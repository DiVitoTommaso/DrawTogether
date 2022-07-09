package com.divito.drawtogether.ui.freedraw

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast.*
import androidx.fragment.app.Fragment
import com.divito.drawtogether.*
import com.divito.drawtogether.databinding.FragmentFreeDrawBinding
import java.io.File
import java.io.IOException

class FreeDrawFragment : Fragment() {

    private lateinit var binding: FragmentFreeDrawBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        // setup handlers
        binding = FragmentFreeDrawBinding.inflate(inflater, container, false)
        binding.freeOk.setOnClickListener { onClick() }

        if (savedInstanceState != null) {
            binding.projectName.setText(savedInstanceState.getString("projectName"))
            binding.freeVertical.isChecked = savedInstanceState.getBoolean("vertical")
            binding.freeHorizontal.isChecked = savedInstanceState.getBoolean("horizontal")
            Log.i("FreeDrawFragment", "State restored")
        }

        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("projectName", binding.projectName.text.toString())
        outState.putBoolean("vertical", binding.freeVertical.isChecked)
        outState.putBoolean("horizontal", binding.freeHorizontal.isChecked)
        Log.i("FreeDrawFragment", "State saved")
    }

    private fun onClick() {
        // check if whiteboard configuration is valid and then start whiteboard activity if configuration is correct
        when {
            binding.projectName.text.isEmpty() ->
                makeText(activity, R.string.title_missing, LENGTH_LONG).show()

            File(
                requireActivity().filesDir,
                "projects/${binding.projectName.text}-portrait.png"
            ).exists()
                    || File(
                requireActivity().filesDir,
                "projects/${binding.projectName.text}-landscape.png"
            ).exists() ->
                makeText(activity, R.string.title_duplicate, LENGTH_LONG).show()

            "/" in binding.projectName.text ->
                makeText(activity, R.string.invalid_project_name, LENGTH_LONG).show()

            !binding.freeVertical.isChecked && !binding.freeHorizontal.isChecked ->
                makeText(activity, R.string.missing_board_type_select, LENGTH_LONG).show()

            else -> {
                // save orientation mode and check if file name is valid or not
                val mode = if (binding.freeVertical.isChecked) ORIENTATION_PORTRAIT else ORIENTATION_LANDSCAPE
                try {
                    val f = File(requireActivity().filesDir, "temp/${binding.projectName.text}")
                    f.createNewFile()
                    f.delete()
                    Log.i("FreeDrawFragment", "File name ok. Whiteboard activity launched")
                }catch (e:IOException){
                    Log.i("FreeDrawFragment", "Invalid file name ${binding.projectName.text}")
                    makeText(activity, R.string.invalid_file_name, LENGTH_LONG).show()
                    return
                }

                startActivity(
                    Intent(requireActivity(), WhiteboardActivity::class.java)
                        .putExtra("orientation", mode)
                        .putExtra("projectName", binding.projectName.text.toString())
                )
                binding.projectName.text.clear()
                binding.freeVertical.isChecked = false
                binding.freeHorizontal.isChecked = false
            }

        }
    }
}