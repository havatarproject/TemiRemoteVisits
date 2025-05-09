package com.temiremotevisits.utils

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.firebase.auth.FirebaseAuth
import com.temiremotevisits.service.MainServiceRepository

class Utils {
    companion object {
        fun addLogoutOnBackPressedCallback(
            activity: FragmentActivity,
            mainServiceRepository: MainServiceRepository
        ) {
            activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    AlertDialog.Builder(activity)
                        .setTitle("Logout")
                        .setMessage("Are you sure you want to logout?")
                        .setPositiveButton("Yes") { _, _ ->
                            mainServiceRepository.stopService()
                            FirebaseAuth.getInstance().signOut()
                            activity.finish()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .create()
                        .show()
                }
            })
        }

        fun addOnBackPressedCallback(activity: FragmentActivity) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
                    // Trigger the normal back press behavior
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }
            activity.onBackPressedDispatcher.addCallback(activity, callback)
        }


        fun encodeEmail(username: String?): String {
            return username!!.replace(".", ",")
        }

        fun decodeEmail(encodedEmail: String): String {
            return encodedEmail.replace(",", ".")
        }
    }
}