package com.temiremotevisits

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.temiremotevisits.databinding.ActivityCallBinding
import com.temiremotevisits.service.MainService
import com.temiremotevisits.service.MainServiceRepository
import com.temiremotevisits.utils.convertToHumanTime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : AppCompatActivity(), MainService.EndCallListener {

    private var target: String? = null
    private var isVideoCall: Boolean = true
    private var isCaller: Boolean = true

    private var isMicrophoneMuted = false
    private var isCameraMuted = true
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    // private var callTimeSeconds = 0
    // CoroutineScope to manage the call termination timer coroutine
    // private val timerCoroutineScope = CoroutineScope(Dispatchers.Default + Job())

    @Inject lateinit var serviceRepository: MainServiceRepository
    @Inject lateinit var temiController: TemiController
    private lateinit var requestScreenCaptureLauncher: ActivityResultLauncher<Intent>

    private lateinit var views: ActivityCallBinding

    companion object {
        private const val TAG = "CallActivity"
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart called")
        requestScreenCaptureLauncher = registerForActivityResult(ActivityResultContracts
            .StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data
                MainService.screenPermissionIntent = intent
                Log.d(TAG, "Screen capture permission granted")
            } else {
                Log.e(TAG, "Screen capture permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityCallBinding.inflate(layoutInflater)
        setContentView(views.root)
        Log.d(TAG, "onCreate called")
        init()
    }

//    fun startCallTerminationThread() {
//        timerCoroutineScope.launch {
//            Log.d(TAG, "startCallTerminationThread called")
//            while (isActive) { // Loop while the coroutine is active
//                val endTime = 1000 * 60 * 3 // 3 minutes
//                val buffer = 1000 * 60 * 2 // 2 minutes
//                if (callTimeSeconds >= (endTime - buffer)) {
//                    Log.d(TAG, "Call ended due to timeout")
//                    serviceRepository.sendEndCall()
//                    callTimeSeconds = 0
//                    break // Exit the loop after finishing
//                }
//                Log.d(TAG, "(startCallTerminationThread) Call timer: ${callTimeSeconds.convertToHumanTime()}")
//                delay(1000) // Check every second (adjust as needed)
//            }
//        }
//    }

    private fun init() {
        Log.d(TAG, "Initializing CallActivity")
        intent.getStringExtra("target")?.let {
            this.target = it
        } ?: run {
            Log.e(TAG, "Target is null, finishing activity")
            finish()
            return
        }

        isVideoCall = intent.getBooleanExtra("isVideoCall", true)
        isCaller = intent.getBooleanExtra("isCaller", true)

        views.apply {
            callTitleTv.text = "In call with $target"
            activityScope.launch(Dispatchers.IO) {
                for (i in 0..3600) {
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        callTimerTv.text = i.convertToHumanTime()
                        // callTimeSeconds = i
                        Log.d(TAG, "Call timer updated: ${i.convertToHumanTime()}")
                    }
                }
            }

            if (!isVideoCall) {
                views.toggleCameraButton.isVisible = false
                Log.d(TAG, "Video call is disabled, hiding camera toggle button")
            } else {
                //serviceRepository.toggleVideo(isCameraMuted)
                views.toggleCameraButton.isVisible = true
            }

            MainService.remoteSurfaceView = remoteView
            MainService.localSurfaceView = localView
            serviceRepository.setupViews(isVideoCall, isCaller, target!!)
            serviceRepository.toggleVideo(!isCameraMuted)
            endCallButton.setOnClickListener {
                Log.d(TAG, "End call button clicked")
                serviceRepository.sendEndCall()
            }

            setupMicToggleClicked()
            setupCameraToggleClicked()
        }

        MainService.endCallListener = this
    }

    private fun setupMicToggleClicked() {
        views.apply {
            toggleMicrophoneButton.setOnClickListener {
                if (!isMicrophoneMuted) {
                    serviceRepository.toggleAudio(true)
                    toggleMicrophoneButton.setImageResource(R.drawable.ic_mic_on)
                    Log.d(TAG, "Microphone muted")
                } else {
                    serviceRepository.toggleAudio(false)
                    toggleMicrophoneButton.setImageResource(R.drawable.ic_mic_off)
                    Log.d(TAG, "Microphone unmuted")
                }
                isMicrophoneMuted = !isMicrophoneMuted
            }
        }
    }

    private fun setupCameraToggleClicked() {
        views.apply {
            toggleCameraButton.setOnClickListener {
                if (isCameraMuted) {
                    serviceRepository.toggleVideo(true)
                    toggleCameraButton.setImageResource(R.drawable.ic_camera_on)
                    Log.d(TAG, "Camera muted")
                } else {
                    serviceRepository.toggleVideo(false)
                    toggleCameraButton.setImageResource(R.drawable.ic_camera_off)
                    Log.d(TAG, "Camera unmuted")
                }
                isCameraMuted = !isCameraMuted
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        Log.d(TAG, "Back button pressed, ending call")
        serviceRepository.sendEndCall()
    }

    override fun onCallEnded() {
        //temiController.moveToLocation("work base") //TODO
        Log.d(TAG, "Call ended, finishing activity")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        activityScope.cancel()
        // timerCoroutineScope.cancel()
        MainService.remoteSurfaceView?.release()
        MainService.remoteSurfaceView = null

        MainService.localSurfaceView?.release()
        MainService.localSurfaceView = null
    }
}
