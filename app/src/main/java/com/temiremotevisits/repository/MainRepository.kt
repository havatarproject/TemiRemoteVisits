package com.temiremotevisits.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.temiremotevisits.TemiController
import com.temiremotevisits.firebaseClient.FirebaseClient
import com.temiremotevisits.utils.Appointment
import com.temiremotevisits.utils.AppointmentScheduler
import com.temiremotevisits.utils.DataModel
import com.temiremotevisits.utils.DataModelType
import com.temiremotevisits.utils.UserStatus
import com.temiremotevisits.webrtc.MyPeerObserver
import com.temiremotevisits.webrtc.WebRTCClient
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MainRepository @Inject constructor(
    private val firebaseClient: FirebaseClient,
    private val webRTCClient: WebRTCClient,
    private val gson: Gson,
    private val context: Context
) : WebRTCClient.Listener {

    private var target: String? = null
    var listener: Listener? = null
    private var remoteView: SurfaceViewRenderer?=null
    @Inject lateinit var temiController: TemiController
    private val appointmentScheduler = AppointmentScheduler(context)

    // variables
    var location: String? = null
    var time: String? = null

    private fun parseDateTime(date: String, time: String): Calendar {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        Log.d("parseDateTime", "Input date: $date, time: $time")

        val parsedDate = dateFormat.parse(date)
        val parsedTime = timeFormat.parse(time)

        Log.d("parseDateTime", "Parsed date: $parsedDate")
        Log.d("parseDateTime", "Parsed time: $parsedTime")

        val calendar = Calendar.getInstance()
        parsedDate?.let {
            calendar.time = it
            Log.d("parseDateTime", "Calendar after setting date: ${calendar.time}")
        }

        parsedTime?.let {
            val timeCalendar = Calendar.getInstance()
            timeCalendar.time = it
            calendar.set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
            calendar.set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
            calendar.set(Calendar.SECOND, timeCalendar.get(Calendar.SECOND))
            Log.d("parseDateTime", "Calendar after setting time: ${calendar.time}")
        }

        // Log the final calendar value
        Log.d("parseDateTime", "Final calendar: ${calendar.time}")

        return calendar
    }


    fun scheduleAppointment(appointment: Appointment) {
        appointmentScheduler.scheduleNextAppointment(appointment)
    }

    //fun cancelAppointment(appointmentId: String) {
//        appointmentScheduler.cancelAppointment(appointmentId)
  //  }

    fun login(username: String, password: String, isDone: (Boolean, String?) -> Unit) {
        firebaseClient.login(username, password, isDone)
    }

    fun observeUsersStatus(status: (List<Pair<String, String>>) -> Unit) {
        firebaseClient.observeUsersStatus(status)
    }

    fun sendLocations(locations: List<String>) {
        firebaseClient.sendLocations(locations)
    }

    fun initFirebase() {
        firebaseClient.subscribeForLatestEvent(object : FirebaseClient.Listener {
            override fun onLatestEventReceived(event: DataModel) {
                listener?.onLatestEventReceived(event)
                Log.d("WebRTCClient", "(MainRepository - initFirebase) : event received")
                when (event.type) {
                    DataModelType.Offer -> {
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : Offer received")
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : Offer received ${event.data.toString()}"
                        )
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : DataModelType.Offer received")
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : calling webRTCClient.onRemoteSessionReceived")
                        webRTCClient.onRemoteSessionReceived(
                            SessionDescription(
                                SessionDescription.Type.OFFER,
                                event.data.toString()
                            )
                        )
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : called webRTCClient.onRemoteSessionReceived")
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : target is $target")
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : calling webRTCClient.answer()")
                        target?.let {
                            webRTCClient.answer(it)
                        } ?: run {
                            // Handle the case when target is null
                            Log.e("WebRTCClient", "(MainRepository - initFirebase) : Target is null during Offer")
                            Log.e("initFirebase", "Target is null during Offer")
                        }
                    }
                    DataModelType.Answer -> {
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : Answer received")
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : Answer received ${event.data.toString()}")
                        Log.d("WebRTCClient", "(MainRepository - initFirebase) : DataModelType.Answer received")
                        webRTCClient.onRemoteSessionReceived(
                            SessionDescription(
                                SessionDescription.Type.ANSWER,
                                event.data.toString()
                            )
                        )
                    }
                    DataModelType.IceCandidates -> {
                        val candidate: IceCandidate? = try {
                            gson.fromJson(event.data.toString(), IceCandidate::class.java)
                        } catch (e: Exception) {
                            null
                        }
                        candidate?.let {
                            webRTCClient.addIceCandidateToPeer(it)
                        }
                    }
                    DataModelType.EndCall -> {
                        listener?.endCall()
                    }
                    else -> Unit
                }
            }
        })
    }

    fun sendConnectionRequest(target: String, isVideoCall: Boolean, success: (Boolean) -> Unit) {
        firebaseClient.sendMessageToOtherClient(
            DataModel(
                type = if (isVideoCall) DataModelType.StartVideoCall else DataModelType.StartAudioCall,
                target = target
            ), success
        )
    }

    fun setTarget(target: String) {
        this.target = target
    }

    interface Listener {
        fun onLatestEventReceived(data: DataModel)
        fun endCall()
    }

    fun initWebrtcClient(username: String) {
        webRTCClient.listener = this
        webRTCClient.initializeWebrtcClient(username, object : MyPeerObserver() {

            override fun onAddStream(p0: MediaStream?) {
                super.onAddStream(p0)
                try {
                    p0?.videoTracks?.get(0)?.addSink(remoteView)
                }catch (e:Exception){
                    e.printStackTrace()
                }

            }

            override fun onIceCandidate(p0: IceCandidate?) {
                super.onIceCandidate(p0)
                p0?.let {
                    webRTCClient.sendIceCandidate(target!!, it)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                super.onConnectionChange(newState)
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    changeMyStatus(UserStatus.IN_CALL)
                    firebaseClient.clearLatestEvent()
                }
            }
        })
    }

    fun initLocalSurfaceView(view: SurfaceViewRenderer, isVideoCall: Boolean) {
        webRTCClient.initLocalSurfaceView(view, isVideoCall)
    }

    fun initRemoteSurfaceView(view: SurfaceViewRenderer) {
        webRTCClient.initRemoteSurfaceView(view)
        this.remoteView = view
    }

    fun startCall() {
        webRTCClient.call(target!!)
    }

    fun endCall() {
        webRTCClient.closeConnection()
        changeMyStatus(UserStatus.ONLINE)
    }

    fun sendEndCall() {
        target?.let {
            onTransferEventToSocket(
                DataModel(
                    type = DataModelType.EndCall,
                    target = it
                )
            )
        } ?: run {
            // Handle the case when target is null
            Log.e("sendEndCall", "Target is null")
        }
    }

    private fun changeMyStatus(status: UserStatus) {
        firebaseClient.changeMyStatus(status)
    }

    fun toggleAudio(shouldBeMuted: Boolean) {
        webRTCClient.toggleAudio(shouldBeMuted)
    }

    fun muteAudio(shouldBeMuted: Boolean) {
        webRTCClient.muteRemoteAudio(shouldBeMuted)
    }

    fun toggleVideo(shouldBeMuted: Boolean) {
        webRTCClient.toggleVideo(shouldBeMuted)
    }

    override fun onTransferEventToSocket(data: DataModel) {
        firebaseClient.sendMessageToOtherClient(data) {}
    }

    override fun onCallAnswered() {
        Log.d("MainRepository", "onCallAnswered()")
        temiController.moveToLocation()
        //location?.let { time?.let { it1 -> temiController.moveToLocation(it, it1.toLong()) } }
    }

    fun logOff(function: () -> Unit) = firebaseClient.logOff(function)
    fun cancelReserve(date: String, hour: String) = firebaseClient.cancelReserve(date, hour)
    fun observeAppointments(selectedDate : Date, status: (List<Pair<String, Pair<String, String>>>) -> Unit) {
        firebaseClient.observeAppointments(selectedDate, status)
    }
    fun observeNextConfirmedAppointment(status: (Appointment?) -> Unit) {
        firebaseClient.observeNextConfirmedAppointment { nextAppointment ->
            if (nextAppointment != null) {
                Log.d("observeNextConfirmedAppointment", nextAppointment.toString())
                scheduleAppointment(nextAppointment)
            }
            Log.d("observeNextConfirmedAppointment", nextAppointment.toString())
            status(nextAppointment)
        }
    }
}