package com.divito.drawtogether

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.ConnectionResult.*
import com.google.android.gms.common.GoogleApiAvailability

class LoginActivity : AppCompatActivity() {

    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        supportActionBar?.hide()

        findViewById<Button>(R.id.loginButton).setOnClickListener { login() }

        // check if google play services are supported and enabled
        val gplayStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (gplayStatus != SUCCESS) { // handle cases of google play services missing or disabled etc...
            GoogleApiAvailability.getInstance().showErrorDialogFragment(
                this,
                gplayStatus,
                SUCCESS
            )
            Log.i("LoginActivity", "Google play services error")

        } else
            prepare()
    }

    private fun prepare() {
        // register for login activity
        launcher = registerForActivityResult(
            StartActivityForResult()
        ) {
            if (it.resultCode != RESULT_OK) {
                // if user has not logged in then show an error dialog and exit
                AlertDialog.Builder(this)
                    .setMessage(R.string.login_error)
                    .setPositiveButton(R.string.ok) { _, _ -> }
                    .show()
            } else finish()
        }

        Log.i("LoginActivity", "Login activity ready")
    }

    private fun login() {
        Log.i("LoginActivity", "Login activity launched")
        // open google login menu
        val client = GoogleSignIn.getClient(this, GSO)
        launcher.launch(client.signInIntent)
    }

    private var back = false

    override fun onBackPressed() {
        // listen for double back to exit app (before login)
        if (!back) {
            Log.i("LoginActivity", "First back detected")
            Toast.makeText(this, R.string.exit, Toast.LENGTH_LONG).show()
            back = true
        } else {
            Log.i("LoginActivity", "Second back detected")
            val a = Intent(Intent.ACTION_MAIN)
            a.addCategory(Intent.CATEGORY_HOME)
            a.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(a)
        }
    }
}