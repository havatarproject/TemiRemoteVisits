package com.temiremotevisits

import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.temiremotevisits.databinding.ActivityMainBinding
import com.temiremotevisits.databinding.ActivitySettingsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    private lateinit var views: ActivitySettingsBinding
    private val TAG = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(views.root)
        setupUI()
    }

    private fun setupUI() {
        Log.d(TAG, "setupUI")
        views.backButton.setOnClickListener {
            handleBackPress()
        }

        // Set up the back press dispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        // Custom behavior when the back button is pressed (if needed)
        // Call the default behavior (finishes the activity)
        finish()
    }
}