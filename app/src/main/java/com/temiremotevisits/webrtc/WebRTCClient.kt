package com.temiremotevisits.webrtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.temiremotevisits.utils.DataModel
import com.temiremotevisits.utils.DataModelType
import com.google.gson.Gson
import org.webrtc.*
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRTCClient @Inject constructor(
    private val context: Context,
    private val gson: Gson
) {
    var listener: Listener? = null
    private lateinit var username: String

    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
    private var peerConnection: PeerConnection? = null
    private var isReconnecting = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var target = ""

    private val iceServer = listOf(
        // STUN server
        PeerConnection.IceServer.builder("USERNAME").createIceServer(),
        // TURN servers with username and password
        PeerConnection.IceServer.builder("ADDRESS")
            .setUsername("USERNAME")
            .setPassword("PASSWORD")
            .createIceServer(),
        PeerConnection.IceServer.builder("ADDRESS")
            .setUsername("USERNAME")
            .setPassword("PASSWORD")
            .createIceServer()
    )

    private var localVideoSource: VideoSource? = null
        get() {
            if (field == null) {
                Log.d(TAG, "Creating local video source")
                field = peerConnectionFactory.createVideoSource(false)
            }
            return field
        }

    private val localAudioSource by lazy {
        Log.d(TAG, "Creating local audio source")
        peerConnectionFactory.createAudioSource(MediaConstraints())
    }
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val mediaConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo","true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio","true"))
    }

    private lateinit var localSurfaceView: SurfaceViewRenderer
    private lateinit var remoteSurfaceView: SurfaceViewRenderer
    private var localStream: MediaStream? = null
    private var localTrackId = ""
    private var localStreamId = ""
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    init {
        Log.d(TAG, "Initializing PeerConnectionFactory")
        initPeerConnectionFactory()
    }

    private fun initializeNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Handler(Looper.getMainLooper()).post {
                    Log.d(TAG, "Network available: $network")
                    handleNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Handler(Looper.getMainLooper()).post {
                    Log.d(TAG, "Network lost: $network")
                    isReconnecting = true
                }
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun handleNetworkChange() {
        if (!isReconnecting) return

        peerConnection?.let { pc ->
            if (pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED ||
                pc.connectionState() == PeerConnection.PeerConnectionState.FAILED) {

                Log.d(TAG, "Restarting ICE after network change")
                restartIce()
            }
        }
    }

    private fun restartIce() {
        try {
            // Update ICE configuration
            val rtcConfig = PeerConnection.RTCConfiguration(iceServer).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                enableCpuOveruseDetection = true
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection?.setConfiguration(rtcConfig)

            // Recreate offer if we're the caller
            if (::username.isInitialized) {
                createAndSetLocalOffer()
            }

            isReconnecting = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during ICE restart", e)
        }
    }

    private fun createAndSetLocalOffer() {
        peerConnection?.createOffer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        // Send offer through your existing listener
                        desc?.description?.let { description ->
                            listener?.onTransferEventToSocket(
                                DataModel(
                                    type = DataModelType.Offer,
                                    sender = username,
                                    target = target,
                                    data = description
                                )
                            )
                        }
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        Log.d(TAG, "Creating PeerConnectionFactory")
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBaseContext)
            ).setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBaseContext, true, true
                )
            ).setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = false
                disableEncryption = false
            }).createPeerConnectionFactory().also {
                Log.d(TAG, "PeerConnectionFactory created")
            }
    }

    fun initializeWebrtcClient(
        username: String, observer: PeerConnection.Observer
    ) {
        Log.d(TAG, "Initializing WebRTC client for user: $username")
        this.username = username
        localTrackId = "${username}_track"
        localStreamId = "${username}_stream"
        peerConnection = createPeerConnection(observer)
        if (peerConnection != null) {
            Log.d(TAG, "PeerConnection created successfully")
        } else {
            Log.e(TAG, "Failed to create PeerConnection")
        }
    }

    private fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        var retryCount = 0
        val maxRetries = 5

        while (retryCount < maxRetries) {
            try {
                return peerConnectionFactory.createPeerConnection(iceServer, observer)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating peer connection (attempt ${retryCount + 1}/$maxRetries)", e)
                retryCount++
                if (retryCount < maxRetries) {
                    Thread.sleep(1000) // Wait 1 second before retrying
                }
            }
        }
        return null
    }

    //negotiation section
    fun call(target: String) {
        Log.d(TAG, "Initiating call to $target")
        this@WebRTCClient.target = target
        peerConnection?.createOffer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                Log.d(TAG, "Offer created successfully")
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        Log.d(TAG, "Local description set successfully")
                        listener?.onTransferEventToSocket(
                            DataModel(type = DataModelType.Offer,
                                sender = username,
                                target = target,
                                data = desc?.description)
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    fun answer(target: String) {
        Log.d(TAG, "Answering call from $target")
        peerConnection?.createAnswer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                Log.d(TAG, "Answer created successfully")
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        Log.d(TAG, "Local description set successfully")
                        listener?.onTransferEventToSocket(
                            DataModel(type = DataModelType.Answer,
                                sender = username,
                                target = target,
                                data = desc?.description)
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        Log.d(TAG, "Received remote session description")
        peerConnection?.setRemoteDescription(MySdpObserver(), sessionDescription)
        Log.d(TAG, "SessionDescription" + SessionDescription.Type.ANSWER + " " + sessionDescription.type)
        if (sessionDescription.type == SessionDescription.Type.OFFER) {
            listener?.onCallAnswered()
        }
    }

    fun addIceCandidateToPeer(iceCandidate: IceCandidate) {
        Log.d(TAG, "Adding ICE candidate to peer")
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun sendIceCandidate(target: String, iceCandidate: IceCandidate) {
        Log.d(TAG, "Sending ICE candidate to $target")
        addIceCandidateToPeer(iceCandidate)
        listener?.onTransferEventToSocket(
            DataModel(
                type = DataModelType.IceCandidates,
                sender = username,
                target = target,
                data = gson.toJson(iceCandidate)
            )
        )
    }

    fun toggleAudio2(shouldBeMuted: Boolean) {
        Log.d(TAG, if (shouldBeMuted) "Muting audio" else "Unmuting audio")
        try {
            if (shouldBeMuted) {
                localStream?.removeTrack(localAudioTrack)
            } else {
                localStream?.addTrack(localAudioTrack)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while toggling audio", e)
        }
    }

    fun toggleVideo(shouldBeMuted: Boolean) {
        Log.d(TAG, if (shouldBeMuted) "Stopping video capture" else "Starting video capture")
        try {
            if (shouldBeMuted) {
                stopCapturingCamera()
            } else {
                startCapturingCamera(localSurfaceView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while toggling video", e)
        }
    }

    fun toggleAudio(shouldBeMuted: Boolean) {
        localAudioTrack?.setEnabled(!shouldBeMuted)
    }

    fun toggleVideo2(shouldBeMuted: Boolean) {
        localVideoTrack?.setEnabled(!shouldBeMuted)
    }

    //streaming section
    private fun initSurfaceView(view: SurfaceViewRenderer) {
        Log.d(TAG, "Initializing surface view")
        view.run {
            setMirror(false)
            setEnableHardwareScaler(true)
            init(eglBaseContext, null)
            Log.d(TAG, "Surface view initialized")
        }
    }

    fun initRemoteSurfaceView(view: SurfaceViewRenderer) {
        Log.d(TAG, "Initializing remote surface view")
        this.remoteSurfaceView = view
        initSurfaceView(view)
    }

    fun initLocalSurfaceView(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
        Log.d(TAG, "Initializing local surface view. Is video call? $isVideoCall")
        this.localSurfaceView = localView
        initSurfaceView(localView)
        startLocalStreaming(localView, isVideoCall)
    }

    private fun startLocalStreaming(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
        Log.d(TAG, "Starting local media stream")
        localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)
        Log.d(TAG, "Is video call?: $isVideoCall")

        localAudioTrack = peerConnectionFactory.createAudioTrack(localTrackId + "_audio", localAudioSource)
        localStream?.addTrack(localAudioTrack)

        if (isVideoCall) {
            localVideoTrack = peerConnectionFactory.createVideoTrack(localTrackId + "_video", localVideoSource)
            localStream?.addTrack(localVideoTrack)
        }

        peerConnection?.addStream(localStream)
        Log.d(TAG, "Local media stream started")
    }

    private fun startCapturingCamera(localView: SurfaceViewRenderer) {
        Log.d(TAG, "Starting camera capture")

        Handler(Looper.getMainLooper()).post {
            try {
                localView.init(eglBaseContext, null)
                localView.setEnableHardwareScaler(true)
                localView.setMirror(true) // Mirror for front camera
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SurfaceViewRenderer", e)
                return@post
            }
        }

        val cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            try {
                if (videoCapturer == null) {
                    videoCapturer = getVideoCapturer(context)
                }

                // Release existing surface texture helper
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CameraThread",
                    eglBaseContext
                )

                // Initialize video source if needed
                if (localVideoSource == null) {
                    localVideoSource = peerConnectionFactory.createVideoSource(false)
                }

                videoCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    localVideoSource?.capturerObserver
                )

                videoCapturer?.startCapture(480, 720, 30)
                Log.d(TAG, "Camera capture started")

                Handler(Looper.getMainLooper()).post {
                    try {
                        if (localVideoTrack == null || localVideoTrack?.state() == MediaStreamTrack.State.ENDED) {
                            localVideoTrack = peerConnectionFactory.createVideoTrack(
                                localTrackId + "_video",
                                localVideoSource
                            )
                        }

                        // Remove existing sinks before adding new one
                        localVideoTrack?.removeSink(localView)
                        localVideoTrack?.addSink(localView)

                        if (localStream == null) {
                            localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)
                        }

                        if (!localStream?.videoTracks?.contains(localVideoTrack)!!) {
                            localStream?.addTrack(localVideoTrack)
                        }

                        peerConnection?.addStream(localStream)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up video track and stream", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while starting camera capture", e)
            }
        }
    }

    // Make sure to properly clean up resources
    private fun cleanupCamera() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoTrack?.dispose()
            localVideoTrack = null

            localVideoSource?.dispose()
            localVideoSource = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up camera resources", e)
        }
    }

    private fun startCapturingCamera2(localView: SurfaceViewRenderer) {
        Log.d(TAG, "Starting camera capture")
        val cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            try {
                if (videoCapturer == null) {
                    videoCapturer = getVideoCapturer(context)
                }
                if (surfaceTextureHelper == null) {
                    surfaceTextureHelper = SurfaceTextureHelper.create(
                        Thread.currentThread().name, eglBaseContext
                    )
                }

                videoCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    localVideoSource?.capturerObserver ?: throw IllegalStateException("VideoSource not initialized")
                )

                videoCapturer?.startCapture(720, 480, 20)
                Log.d(TAG, "Camera capture started")

                Handler(Looper.getMainLooper()).post {
                    if (localVideoTrack == null || localVideoTrack?.state() == MediaStreamTrack.State.ENDED) {
                        localVideoTrack = peerConnectionFactory.createVideoTrack(localTrackId + "_video", localVideoSource)
                    }
                    localVideoTrack?.addSink(localView)

                    if (localStream?.videoTracks?.isEmpty() == true) {
                        localStream?.addTrack(localVideoTrack)
                    }
                    peerConnection?.addStream(localStream)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while starting camera capture", e)
            }
        }
    }

    private fun stopCapturingCamera2() {
        Log.d(TAG, "Stopping camera capture")
        val cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            try {
                videoCapturer?.stopCapture()
                Handler(Looper.getMainLooper()).post {
                    try {
                        localVideoTrack?.removeSink(localSurfaceView)
                        localStream?.removeTrack(localVideoTrack)
                        peerConnection?.removeStream(localStream)
                        localSurfaceView?.clearImage()
                        localVideoTrack?.dispose()
                        localVideoTrack = null
                    } catch (e: Exception) {
                        Log.d(TAG, "Error in UI thread while stopping camera capture", e)
                    }
                }
                Log.d(TAG, "Camera capture stopped")
            } catch (e: Exception) {
                Log.d(TAG, "Error while stopping camera capture", e)
            }
        }
    }


    private fun stopCapturingCamera() {
        Log.d(TAG, "Stopping camera capture")
        val cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            try {
                videoCapturer?.stopCapture()
                Handler(Looper.getMainLooper()).post {
                    localVideoTrack?.removeSink(localSurfaceView)
                    /*
                    localVideoTrack?.let { track ->
                        localStream?.removeTrack(track)
                        track.dispose()
                        localVideoTrack = null
                    }
                    localStream?.let { stream ->
                        peerConnection?.removeStream(stream)
                    }
                     */
                    //localStream?.removeTrack(localVideoTrack)
                    //peerConnection?.removeStream(localStream)
                    if (::localSurfaceView.isInitialized) localSurfaceView.clearImage()
                    //localVideoTrack?.dispose()
                    localVideoTrack = null
                }
                Log.d(TAG, "Camera capture stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error while stopping camera capture", e)
            }
        }
    }

    private fun stopCapturingCamera3() {
        val cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            try {
                videoCapturer?.stopCapture()
                Handler(Looper.getMainLooper()).post {
                    localVideoTrack?.removeSink(localSurfaceView)
                    localStream?.let { stream ->
                        localVideoTrack?.let { track ->
                            stream.removeTrack(track)
                            track.dispose()
                        }
                        peerConnection?.removeStream(stream)
                    }
                    localSurfaceView.clearImage()
                    localVideoTrack = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while stopping camera capture", e)
            } finally {
                cameraExecutor.shutdown()
            }
        }
    }

    fun closeConnection() {
        Log.d(TAG, "Closing WebRTC connection")
        try {
            stopCapturingCamera()
            videoCapturer?.dispose()
            localStream?.dispose()
            peerConnection?.close()
            peerConnection = null
            Log.d(TAG, "Connection closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error while closing connection", e)
        }
    }

    private fun getVideoCapturer(context: Context): CameraVideoCapturer {
        Log.d(TAG, "Getting video capturer")
        return if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context).run {
                deviceNames.find {
                    isFrontFacing(it)
                }?.let {
                    createCapturer(it, null)
                } ?: throw IllegalStateException("No front-facing camera found with Camera2.")
            }
        } else {
            Camera1Enumerator(true).run {
                deviceNames.find {
                    isFrontFacing(it)
                }?.let {
                    createCapturer(it, null)
                } ?: throw IllegalStateException("No front-facing camera found with Camera1.")
            }
        }.also {
            Log.d(TAG, "Video capturer obtained")
        }
    }

    fun muteRemoteAudio(mute: Boolean) {
        Log.d(TAG, "Muting remote audio: $mute")
        peerConnection?.receivers?.forEach { receiver ->
            if (receiver.track()?.kind() == "audio") {
                receiver.track()?.setEnabled(!mute)
                Log.d(TAG, "Remote audio ${if (mute) "muted" else "unmuted"}")
            }
        }
    }

    interface Listener {
        fun onTransferEventToSocket(data: DataModel)
        fun onCallAnswered()
    }

    companion object {
        const val TAG: String = "WebRTCClient"
    }
}