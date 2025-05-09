package com.temiremotevisits.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.temiremotevisits.R
import com.temiremotevisits.repository.MainRepository
import com.temiremotevisits.utils.DataModel
import com.temiremotevisits.utils.DataModelType
import com.temiremotevisits.utils.isValid
import com.temiremotevisits.webrtc.RTCAudioManager
import dagger.hilt.android.AndroidEntryPoint
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@AndroidEntryPoint
class MainService : Service(), MainRepository.Listener {

    private val TAG = "MainService"

    private var isServiceRunning = false
    private var username: String? = null

    @Inject
    lateinit var mainRepository: MainRepository

    private lateinit var notificationManager: NotificationManager
    private lateinit var rtcAudioManager: RTCAudioManager
    private var isPreviousCallStateVideo = true


    companion object {
        var listener: Listener? = null
        var endCallListener: EndCallListener?=null
        var localSurfaceView: SurfaceViewRenderer?=null
        var remoteSurfaceView: SurfaceViewRenderer?=null
        var screenPermissionIntent : Intent?=null
    }

    override fun onCreate() {
        super.onCreate()
        rtcAudioManager = RTCAudioManager.create(this)
        rtcAudioManager.setDefaultAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE)
        notificationManager = getSystemService(
            NotificationManager::class.java
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { incomingIntent ->
            when (incomingIntent.action) {
                MainServiceActions.START_SERVICE.name -> handleStartService(incomingIntent)
                MainServiceActions.SETUP_VIEWS.name -> handleSetupViews(incomingIntent)
                MainServiceActions.END_CALL.name -> handleEndCall()
                MainServiceActions.TOGGLE_AUDIO.name -> handleToggleAudio(incomingIntent)
                MainServiceActions.TOGGLE_VIDEO.name -> handleToggleVideo(incomingIntent)
                MainServiceActions.STOP_SERVICE.name -> handleStopService()
                MainServiceActions.MUTE_AUDIO.name -> handleMuteAudio(incomingIntent)
                else -> Unit
            }
        }

        return START_STICKY
    }

    private fun handleStopService() {
        mainRepository.endCall()
        mainRepository.logOff {
            isServiceRunning = false
            stopSelf()
        }
    }

    private fun handleMuteAudio(incomingIntent: Intent) {
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted",true)
        mainRepository.muteAudio(shouldBeMuted)
    }

    private fun handleToggleVideo(incomingIntent: Intent) {
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted",true)
        this.isPreviousCallStateVideo = !shouldBeMuted
        mainRepository.toggleVideo(shouldBeMuted)
    }

    private fun handleToggleAudio(incomingIntent: Intent) {
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted",true)
        mainRepository.toggleAudio(shouldBeMuted)
    }

    private fun handleEndCall() {
        //1. we have to send a signal to other peer that call is ended
        mainRepository.sendEndCall()
        //2.end out call process and restart our webrtc client
        endCallAndRestartRepository()
    }

    private fun endCallAndRestartRepository(){
        mainRepository.endCall()
        endCallListener?.onCallEnded()
        Handler(Looper.getMainLooper()).post {
            mainRepository.initWebrtcClient(username!!)
        }
    }

    private fun handleSetupViews(incomingIntent: Intent) {
        val isCaller = incomingIntent.getBooleanExtra("isCaller",false)
        val isVideoCall = incomingIntent.getBooleanExtra("isVideoCall",true)
        val target = incomingIntent.getStringExtra("target")
        this.isPreviousCallStateVideo = isVideoCall
        mainRepository.setTarget(target!!)
        //initialize our widgets and start streaming our video and audio source
        //and get prepared for call
        mainRepository.initLocalSurfaceView(localSurfaceView!!,isVideoCall)
        mainRepository.initRemoteSurfaceView(remoteSurfaceView!!)

        if (!isCaller){
            //start the video call
            mainRepository.startCall()
        }

    }

    private fun handleStartService(incomingIntent: Intent) {
        //start our foreground service
        if (!isServiceRunning) {
            isServiceRunning = true
            username = incomingIntent.getStringExtra("username")
            startServiceWithNotification()

            mainRepository.listener = this
            mainRepository.initFirebase()
            Handler(Looper.getMainLooper()).post {
                mainRepository.initWebrtcClient(username!!)
            }
        }
    }

    private fun startServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                "channel1", "foreground", NotificationManager.IMPORTANCE_HIGH
            )

            val intent = Intent(this, MainServiceReceiver::class.java).apply {
                action = "ACTION_EXIT"
            }
            val pendingIntent : PendingIntent =
                PendingIntent.getBroadcast(this,0 ,intent,PendingIntent.FLAG_IMMUTABLE)

            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(
                this, "channel1"
            ).setSmallIcon(R.mipmap.ic_launcher)
                .addAction(R.drawable.ic_end_call,"Exit",pendingIntent)

            startForeground(1, notification.build())
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onLatestEventReceived(data: DataModel) {
        if (data.isValid()) {
            when (data.type) {
                DataModelType.StartVideoCall,
                DataModelType.StartAudioCall -> {
                    listener?.onCallReceived(data)
                }
                else -> Unit
            }
        }
    }

    override fun endCall() {
        //we are receiving end call signal from remote peer
        endCallAndRestartRepository()
    }

    interface Listener {
        fun onCallReceived(model: DataModel)
    }

    interface EndCallListener {
        fun onCallEnded()
    }
}