package com.temiremotevisits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.temiremotevisits.repository.MainRepository
import com.temiremotevisits.utils.Appointment
import com.temiremotevisits.utils.Utils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AppointmentReceiver : HiltBroadcastReceiver(){

    @Inject lateinit var mainRepository: MainRepository
    @Inject lateinit var temiController: TemiController

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceiveAlarm: ${intent.action}")
        when (intent.action) {
            "APPOINTMENT_START" -> {
                val appointmentId = intent.getStringExtra("appointmentId")
                Log.d(TAG, "Received APPOINTMENT_START for ID: $appointmentId")
                val appointment = intent.getSerializableExtra("appointment") as? Appointment
                Log.d(TAG, appointment.toString())
                if (appointment != null) {
                    Log.d(TAG, appointment.userEmail)
                }

                if (appointment != null) mainRepository.time = appointment.time
                if (appointment != null) mainRepository.location = appointment.location

                if (appointment != null) {
                    temiController.setTimeStopped(appointment.time.toLong() * 60 * 1000)
                }
                if (appointment != null) {
                    temiController.setNextLocation(appointment.location)
                }

                appointment?.let { apt ->
                    apt.userEmail.let { email ->
                        mainRepository.sendConnectionRequest(Utils.encodeEmail(email), isVideoCall = true) { success ->
                            Log.d(TAG, "Connection request sent: $success")
                            if (success) {
                                val callIntent = Intent(context, CallActivity::class.java).apply {
                                    putExtra("target", Utils.encodeEmail(email))
                                    putExtra("isVideoCall", true)
                                    putExtra("isCaller", true)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                ContextCompat.startActivity(context, callIntent, null)
                            }
                        }
                    }
                }
            }
            "APPOINTMENT_END" -> {
                val appointmentId = intent.getStringExtra("appointmentId")
                Log.d(TAG, "Received APPOINTMENT_END for ID: $appointmentId")
            }
        }
    }

    companion object {
        const val TAG = "AppointmentReceiver"
    }
}

abstract class HiltBroadcastReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) {}
}