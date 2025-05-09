package com.temiremotevisits

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.temiremotevisits.adapters.AppointmentsRecyclerViewAdapter
import com.temiremotevisits.databinding.ActivityMainBinding
import com.temiremotevisits.repository.MainRepository
import com.temiremotevisits.service.MainService
import com.temiremotevisits.service.MainServiceRepository
import com.temiremotevisits.utils.AppointmentData
import com.temiremotevisits.utils.DataModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MainService.Listener, AppointmentsRecyclerViewAdapter.Listener,
    OnRobotReadyListener {
    private val TAG = "MainActivity"
    @Inject
    lateinit var mainRepository: MainRepository
    @Inject
    lateinit var mainServiceRepository: MainServiceRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var views: ActivityMainBinding
    private var selectedDate: Date? = null
    private var mainAdapter: AppointmentsRecyclerViewAdapter? = null
    private lateinit var firestore: FirebaseFirestore
    private var username: String? = null
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var robot: Robot

    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 60000L

    private val appointmentChecker = object : Runnable {
        override fun run() {
            checkAppointments()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionManager = PermissionManager(this)
        if (permissionManager.handlePermissionResult(requestCode, grantResults, this)) {
            // Permissions granted, initialize your app
            init()
        } else {
            // Permissions denied, dialog shown by permissionManager, no further action needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        robot = Robot.getInstance()
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        permissionManager = PermissionManager(this)
        val permissionManager = PermissionManager(this)

        if (!permissionManager.permissionsGranted()) {
            permissionManager.checkAndRequestPermissions(this)
        } else {
            init()
        }
    }

    private fun init() {
        firebaseAuth = FirebaseAuth.getInstance()
        signInUser("dindooown@hotmail.com","060100")
        subscribeObservers()
        startMyService()
        setupUI()

        firestore = FirebaseFirestore.getInstance()
        //Utils.addOnBackPressedCallback(this)
    }

    private fun signInUser(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = firebaseAuth.currentUser
                    if (user != null && user.isEmailVerified) {
                        Log.d(TAG, "signInUser")
                        loginUser(email, password)
                    } else {
                        Log.d(TAG, "signInUserFail1")
                    }
                } else {
                    Log.d(TAG, "signInUserFail2")
                }
            }
    }

    private fun loginUser(email: String, password: String) {
        mainRepository.login(
            email,email
        ){ isDone, reason ->
            if (!isDone){
                Toast.makeText(this@MainActivity, reason, Toast.LENGTH_SHORT).show()
            }else{
                Toast.makeText(this@MainActivity, "Smooth", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        Log.d(TAG, "setupUI")
        views.apply {
            datePickerButton.setOnClickListener {
                Log.d(TAG, "datePickerButton")
                showDatePickerDialog()
            }
        }
        views.apply {
            exitButton.setOnClickListener {
                Log.d(TAG, "button (exit app)")
                finishAffinity()
            }
        }
        views.apply {
            settingsButton.setOnClickListener {
                Log.d(TAG, "settingsButton")
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java).apply {
                })
            }
        }
        views.apply {
            callButton.setOnClickListener {
                Log.d(TAG, "callButton")
                startActivity(Intent(this@MainActivity, CallsActivity::class.java).putExtra("username", username).apply {
                })
            }
        }

        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        calendar.set(year, month, day)
        selectedDate = calendar.time
        val dateFormat = SimpleDateFormat("dd-MM-yyyy")
        views.selectedDateTV.text = selectedDate?.let { dateFormat.format(it).toString() }
        subscribeObservers()
    }

    private fun setupRecyclerView() {
        mainAdapter = AppointmentsRecyclerViewAdapter(this)
        val layoutManager = LinearLayoutManager(this)
        views.appointmentsRecyclerView.apply {
            setLayoutManager(layoutManager)
            adapter = mainAdapter
        }
    }

    private fun subscribeObservers() {
        setupRecyclerView()
        MainService.listener = this

        selectedDate?.let { date ->
            mainRepository.observeAppointments(date) {
                Log.d("TAG", "subscribeObservers: $it")
                mainAdapter?.updateList(it)
            }
        }

        views.apply {
            mainRepository.observeNextConfirmedAppointment { appointment ->
                Log.d("TAG", appointment.toString())
                nextAppointmentDateTV.text = if (appointment != null) {
                    getString(R.string.next_appointment_text, appointment.date, appointment.hour)
                } else {
                    getString(R.string.no_reservations)
                }
            }
        }
    }

    private fun scheduleCalls(appointments: List<AppointmentData>) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (appointment in appointments) {
            val appointmentDate = dateFormat.parse("${appointment.date} ${appointment.hour}")
            val eventTime = appointmentDate?.time

            val currentTime = System.currentTimeMillis()
            val delay = eventTime?.minus(currentTime)

            if (delay != null) {
                if (delay > 0) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        initiateCall(appointment)
                    }, delay)
                } else {
                    Log.d("TAG", "Event time has already passed: $appointment")
                }
            }
        }
    }


    private fun initiateCall(appointment: AppointmentData) {
        Log.d("WebRTCClient", "(MainActivity) : initiateCall to ${appointment.userEmail}")
        appointment.userEmail?.let { it ->
            Log.d("WebRTCClient", "(MainActivity) : sendConnectionRequest to $it")
            mainRepository.sendConnectionRequest(it, false) {
                if (it){
                    Log.d("WebRTCClient", "(MainActivity) : start activity CallActivity")
                    startActivity(Intent(this, CallActivity::class.java).apply {
                        putExtra("target",username)
                        putExtra("isVideoCall",false)
                        putExtra("isCaller",true)
                    })
                }
            }
        }
        Log.d("WebRTCClient", "(MainActivity) : initiateCall finished")
    }

    private fun startAppointmentNotifications() {
        // Implement a background service or use WorkManager to check for appointments and show notifications
    }

    private fun initializeApp() {
        setContentView(R.layout.activity_main)
        //val robot = Robot.getInstance()
    }

    override fun onResume() {
        super.onResume()
        handler.post(appointmentChecker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(appointmentChecker)
    }

    private fun checkAppointments() {
        // does nothing jon snow
        Log.d("checkAppointments", "appoints")
    }


    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePickerDialog = DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
            calendar.set(selectedYear, selectedMonth, selectedDay)
            selectedDate = calendar.time
            val dateFormat = SimpleDateFormat("dd-MM-yyyy")
            views.selectedDateTV.text = selectedDate?.let { dateFormat.format(it).toString() }
            subscribeObservers()
        }, year, month, day)
        datePickerDialog.show()
    }

    private fun startMyService() {
        username = FirebaseAuth.getInstance().currentUser?.email.toString()
        Log.d(TAG, "startMyService $username")
        mainServiceRepository.startService(username!!)
        Log.d(TAG, robot.locations.toString())
    }

    override fun cancelClicked(date: String, hour: String) {
        mainRepository.cancelReserve(date, hour)
    }

    override fun onCallReceived(model: DataModel) {
        // does nothing
        TODO("Not yet implemented")
    }

    override fun onRobotReady(isReady: Boolean) {
        Log.d(TAG, robot.locations.toString())
        mainRepository.sendLocations(robot.locations)
    }

    override fun onStart() {
        super.onStart()
        robot = Robot.getInstance()
        robot.addOnRobotReadyListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeOnRobotReadyListener(this)
    }
}