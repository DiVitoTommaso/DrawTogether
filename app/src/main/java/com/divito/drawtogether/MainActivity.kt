package com.divito.drawtogether

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.divito.drawtogether.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.navigation.NavigationView
import java.io.File

const val APP_ID = "524eb81e-ba9f-4328-b7e6-759ce7321f76"

// constants indicating screen orientation (portrait => vertical, landscape => horizontal)
const val ORIENTATION_UNLOCKED = "unlocked"
const val ORIENTATION_PORTRAIT = "portrait"
const val ORIENTATION_LANDSCAPE = "landscape"

// google account options
val GSO = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
    .requestProfile()
    .requestEmail()
    .build()

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var loginLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // create directories
        File(filesDir, "projects").mkdir()
        File(filesDir, "temp images").mkdir()
        // clear project temp directory
        val tmp = File(filesDir, "temp")
        tmp.delete()
        tmp.mkdir()

        Log.i("MainActivity", "Directories ready")

        val binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root) // load view
        setSupportActionBar(binding.appBarMain.toolbar) // setup action bar

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // setup nav view
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home, R.id.nav_free_draw, R.id.nav_picture_draw
            ), drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
        setupNavView()

        // register login activity
        loginLauncher =
            registerForActivityResult(StartActivityForResult()) { setupNavView() }

        // if user is not logged in then start login activity
        if (!isLoggedIn()) {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
            Log.i("MainActivity", "User not logged. Login asked")
        }
    }

    private fun isLoggedIn() = GoogleSignIn.getLastSignedInAccount(this) != null

    private fun setupNavView() {
        // get logged account or return if still not logged
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        // if user is logged then get information about his profile and setup the nav view
        val navHeader = findViewById<NavigationView>(R.id.nav_view).getHeaderView(0) as LinearLayout
        val image = navHeader.findViewById<ImageView>(R.id.image)
        val username = navHeader.findViewById<TextView>(R.id.username)
        val email = navHeader.findViewById<TextView>(R.id.email)

        username.text = account.displayName
        email.text = account.email

        // external library to manage automatically photo download, caching and error handling
        Glide.with(this)
            .load(account.photoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(image)

        Log.i("MainActivity", "User account information set successfully")

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        super.onOptionsItemSelected(item)
        // check item selected in action bar
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_account -> {
                GoogleSignIn.getClient(this, GSO).signOut()
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
                Log.i("MainActivity", "User login activity launched")

            }
            R.id.action_contact ->
                with(Intent(Intent.ACTION_SENDTO)) {
                    data = Uri.parse("mailto:") // only email apps should handle this
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("t.divito@studenti.unipi.it"))
                    putExtra(Intent.EXTRA_SUBJECT, "Feedback")
                    startActivity(this)
                    Log.i("MainActivity", "Mail activity launched")

                }
            // cannot find right id -.-'
            else -> {
                onSupportNavigateUp()
                Log.i("MainActivity", "NavView opened")
            }
        }

        return true
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // create menu and setup with google account information
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}