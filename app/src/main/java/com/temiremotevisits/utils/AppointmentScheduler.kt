package com.temiremotevisits.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.temiremotevisits.AppointmentReceiver
import com.temiremotevisits.AppointmentReceiver.Companion.TAG
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AppointmentScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleNextAppointment(appointment: Appointment) {
        Log.d("AppointmentScheduler", "Scheduling next appointment with ID: ${appointment.id}")
        Log.d("AppointmentScheduler", appointment.toString())
        if (canScheduleExactAlarms()) {
            Log.d("AppointmentScheduler", "Can schedule exact alarms. Proceeding with exact alarm scheduling.")
            scheduleExactAlarm(appointment)
        } else {
            Log.d("AppointmentScheduler", "Cannot schedule exact alarms. Falling back to inexact alarm scheduling.")
            scheduleInexactAlarm(appointment)
        }
    }

    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val canSchedule = alarmManager.canScheduleExactAlarms()
            Log.d("AppointmentScheduler", "Can schedule exact alarms (SDK >= S): $canSchedule")
            canSchedule
        } else {
            Log.d("AppointmentScheduler", "Exact alarms supported (SDK < S)")
            true
        }
    }

    private fun scheduleExactAlarm(appointment: Appointment) {
        val intent = Intent(context, AppointmentReceiver::class.java).apply {
            action = "APPOINTMENT_START"
            Log.d(TAG, "APPOINTMENT_START " + appointment.id)
            putExtra("appointmentId", appointment.id)
            putExtra("appointment", appointment)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appointment.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val calendar = getCalendarFromAppointment(appointment.date, appointment.hour)
            calendar.let {
                Log.d("AppointmentScheduler", "Scheduling exact alarm at: ${it.time}")
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    it.timeInMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.e("AppointmentScheduler", "SecurityException caught while scheduling exact alarm. Falling back to inexact alarm.", e)
            scheduleInexactAlarm(appointment)
        }
    }

    fun getCalendarFromAppointment(date: String, time: String): Calendar {
        val dateTimeString = "$date $time"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateTime = dateFormat.parse(dateTimeString)
        return Calendar.getInstance().apply {
            if (dateTime != null) {
                this.time = dateTime
            }
        }
    }

    private fun scheduleInexactAlarm(appointment: Appointment) {
        val intent = Intent(context, AppointmentReceiver::class.java).apply {
            action = "APPOINTMENT_START"
            putExtra("appointmentId", appointment.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appointment.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = getCalendarFromAppointment(appointment.date, appointment.hour)
        calendar.let {
            Log.d("AppointmentScheduler", "Scheduling inexact alarm at: ${it.time}")
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                it.timeInMillis,
                pendingIntent
            )

        }
    }

    fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d("AppointmentScheduler", "Requesting exact alarm permission.")
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            context.startActivity(intent)
        }
    }

    fun cancelAlarm(appointmentId: String) { //

    }
}
