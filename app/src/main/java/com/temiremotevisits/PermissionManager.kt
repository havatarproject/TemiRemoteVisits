package com.temiremotevisits

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
class PermissionManager(private val context: Context) {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.CAMERA,
        //android.Manifest.permission.INTERNET,
        //android.Manifest.permission.ACCESS_WIFI_STATE,
        //android.Manifest.permission.BLUETOOTH
    )

    fun checkAndRequestPermissions(activity: Activity) {
        val permissionsToRequest = mutableListOf<String>()

        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    fun permissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun handlePermissionResult(requestCode: Int, grantResults: IntArray, activity: Activity): Boolean {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                return true
            } else {
                showPermissionDeniedDialog(activity)
                return false
            }
        }
        return false
    }

    private fun showPermissionDeniedDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage("This app requires all permissions to function properly. Without these permissions, the app cannot continue. Would you like to grant the permissions?")
            .setPositiveButton("Grant Permissions") { _, _ ->
                checkAndRequestPermissions(activity)
            }
            .setNegativeButton("Exit App") { _, _ ->
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }
}