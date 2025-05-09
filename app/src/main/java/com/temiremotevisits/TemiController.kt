package com.temiremotevisits

import android.content.Context
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import android.os.Handler
import android.os.Looper
import com.temiremotevisits.service.MainService
import com.temiremotevisits.service.MainServiceRepository
import com.temiremotevisits.utils.DataModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemiController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mainRepository: MainServiceRepository
) : OnGoToLocationStatusChangedListener, MainService.Listener {

    private val robot: Robot = Robot.getInstance()
    private var initialPosition: String? = "work base"
    private var handler: Handler? = null
    private var delayedRunnable: Runnable? = null
    private var nextLocation: String? = "home base"
    private var timeStopped: Long? = 12000L

    private val TAG = "TemiController"

    init {
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    fun setNextLocation(location: String) {
        nextLocation = location
    }
    fun setTimeStopped(time: Long) {
        timeStopped = time
    }


    fun moveToLocation() {
        //mainRepository.muteCall(true)
        delayedRunnable?.let { handler?.removeCallbacks(it) }
        Log.d(TAG, "moveToLocation $nextLocation")
        Log.d(TAG, "timeStopped $timeStopped")

        nextLocation?.let { robot.goTo(it) }

        handler = Handler(Looper.getMainLooper())
        delayedRunnable = Runnable {
            //mainRepository.sendEndCall()
            //initialPosition?.let { robot.goTo(it) } //TODO activate
        }

        timeStopped?.let { handler?.postDelayed(delayedRunnable!!, it) }
    }

    fun moveToLocation(location: String) {
        // Cancel the delayed task to prevent the first function from executing
        delayedRunnable?.let { handler?.removeCallbacks(it) }
        Log.d(TAG, "moveToLocation $location")
        robot.goTo(location)
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        Log.d("onGoToLocationStatusChanged", "onGoToLocationStatusChanged" + status + " " + location)

        when (status) {
            OnGoToLocationStatusChangedListener.START -> {
                //do nothing
            }
            OnGoToLocationStatusChangedListener.COMPLETE -> {
                if (location == initialPosition) {
                    Log.d("onGoToLocationStatusChanged", location)
                } else {
                    Log.d("onGoToLocationStatusChanged", location)
                    //Toast.makeText(context, "Reached $location", Toast.LENGTH_SHORT).show()
                    //mainRepository.muteCall(false)
                    //mainRepository.toggleVideo(false)
                    robot.tiltAngle(20)
                }
            }
            OnGoToLocationStatusChangedListener.ABORT -> {
                //mainRepository.sendEndCall()
                //robot.goTo("work base")
            }
            else -> {
                // Handle other possible status values (if any)
                Log.e("onGoToLocationStatusChanged", "Unexpected status: $status")
            }
        }
    }
    override fun onCallReceived(model: DataModel) {
        //Does nothing
    }
}