package com.example.mediasoupdemoimp

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.example.mediasoupdemoimp.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONException
import org.json.JSONObject
import org.mediasoup.droid.Consumer
import org.mediasoup.droid.Device
import org.mediasoup.droid.MediasoupClient
import org.mediasoup.droid.Producer
import org.mediasoup.droid.RecvTransport
import org.mediasoup.droid.SendTransport
import org.mediasoup.droid.Transport
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.URI
import java.nio.ByteBuffer


class MainActivity : AppCompatActivity() {
    var routerCapabilities = 2121
    var webRtcTransport = 2213
    var joinEvent = 32213
    var connectWebRtcEvent = 32451
    lateinit var protooClient: ProtooClient
    lateinit var mMediasoupDevice: Device
    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var localVideoTrack: VideoTrack
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var videoCapturer: VideoCapturer
    var icePaCandData: String = ""
    var rtpCapabilties: JSONObject? = null
    val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        MediasoupClient.initialize(applicationContext)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        mMediasoupDevice = Device()
        initWebRTC()

        val serverUri =
            URI("wss://mediasoup-dev.zillit.com/protoo?roomId=lenqzxib&peerId=ybgy2mey") // Change this to your Protoo server address
        protooClient = ProtooClient(serverUri)
        protooClient.connect()
        protooClient.onOpen = {
            protooClient.sendMessage(JSONObject().apply {
                put("request", true)
                put("id", routerCapabilities)
                put("method", "getRouterRtpCapabilities")
                put("data", {})
            }.toString())
            createLocalStream(this@MainActivity)
        }
        protooClient.onMessageHandler = {
            val json = JSONObject(this)
            if (json.has("id") && json.getString("id") == routerCapabilities.toString()) {
                val capabilities = json.getJSONObject("data")
                rtpCapabilties = capabilities
                mMediasoupDevice.load(capabilities.toString(), null)
                Log.d("Protoo", "Router RTP Capabilities: $capabilities")
                createWebRtcTransport()
            }
            if (json.has("id") && json.getString("id") == webRtcTransport.toString()) {
                val capabilities = json.getJSONObject("data")
//                mMediasoupDevice.load(capabilities.toString(), null)
                Log.d("Protoo", "webRtcTransport: $capabilities")
                createSendTransport(capabilities.toString())
//                createWebRtcTransport()
                joinRoom()
            }
            if (json.has("id") && json.getString("id") == joinEvent.toString()) {
                val capabilities = json.getJSONObject("data")
//                mMediasoupDevice.load(capabilities.toString(), null)
                Log.d("Protoo", "JoinEventRes: $capabilities")
//                createWebRtcTransport()
                createRecvTransport()

//                joinRoom()
            }
            if (json.has("id") && json.getString("id") == connectWebRtcEvent.toString()) {
//                val capabilities = json.getJSONObject("data")
//                mMediasoupDevice.load(capabilities.toString(), null)
                Log.d("Protoo", "connectWebRtcTransportEvent: $this")
//                createWebRtcTransport()
//                joinRoom()
            }

            if (json.has("method") && json.getString("method") == "newProducer") {
                val producerId =
                    json.getJSONObject("data").getString("producerId") // ID of new producer
                Log.d("Mediasoup", "New producer detected: $producerId")
//                consumeRemoteStream(producerId)
            }
            if (json.has("method") && json.getString("method") == "newConsumer") {
                val producerId =
                    json.getJSONObject("data").getString("producerId") // ID of new producer
                Log.e("Mediasoup", "New producer detected: $producerId")
                val data = json.getJSONObject("data")
                val consumerId = data.getString("id")
                val kind = data.getString("kind")

                val rtpParameters = data.getJSONObject("rtpParameters")

                Log.e("Mediasoup", "Consuming stream: $consumerId ($kind)")

                val consumer = recvTransport.consume(
                    object : Consumer.Listener {
                        override fun onTransportClose(consumer: Consumer) {
                            Log.d("Mediasoup", "Consumer transport closed")
                        }
                    },
                    consumerId,
                    data.getString("producerId"),
                    kind,
                    rtpParameters.toString(),
                    null
                )
                Log.e("Mediasoup", "Consuming Track: ${consumer?.track} (${consumer.isPaused})")
                if (kind == "video") {
                    val remoteVideoTrack = consumer?.track as VideoTrack
                    playRemoteVideo(remoteVideoTrack)

                } else if (kind == "audio") {
                    val remoteAudioTrack = consumer.track as AudioTrack
                    playRemoteAudio(remoteAudioTrack)
                }
//                consumeRemoteStream(producerId)
            }

            if (json.has("id") && json.getInt("id") == 5) {

            }
        }


    }

    fun playRemoteAudio(track: AudioTrack) {
        val stream = peerConnectionFactory.createLocalMediaStream("remoteStream")
        stream.addTrack(track)
    }

    fun setupRemoteView(context: Context) {
//        binding.surfaceView = SurfaceViewRenderer(context)

        binding.surfaceView.apply {
            setMirror(false)
            setEnableHardwareScaler(true)
            init(EglBase.create().eglBaseContext, null)

        }
    }

    fun playRemoteVideo(videoTrack: VideoTrack) {
        Log.d("Mediasoup", "Attaching track to renderer for consumerId: $videoTrack")
        runOnUiThread {
            setupRemoteView(this)
            if (videoTrack != null) {
                binding.surfaceView.post {
                    videoTrack.removeSink(binding.surfaceView)
                    binding.surfaceView.requestLayout()
                    videoTrack.addSink(binding.surfaceView)
                    videoTrack.setEnabled(true)
                    binding.surfaceView.requestLayout()
//                    binding.surfaceView.invalidate()
                    Log.d(
                        "Mediasoup",
                        "🎥 Attaching Video Track: ${videoTrack.id()} binding.surfaceView.isVisible ${binding.surfaceView.isVisible}"
                    )
                    binding.surfaceView.isVisible = true
                }

            } else {
                Log.e("Mediasoup", "⚠️ Video track is NULL")
            }
//            binding.surfaceView.addFrameListener({ frame ->
//                Log.d("Mediasoup", "✅ Video Frame Received: ${frame}")
//            }, 1.0f)
        }

    }

    fun consumeRemoteStream(producerId: String) {
        val info: JSONObject = JSONObject(icePaCandData)
        Log.e("Paramester ", "createSendTransport $info")
        val dtlsParameters = info.getJSONObject("dtlsParameters")
        val request = JSONObject().apply {
            put("request", true)
            put("method", "consume")
            put("id", 5)
            put("data", JSONObject().apply {
                put("transportId", recvTransport.id)
                put("producerId", producerId)
                put("paused", false)
                put("dtlsParameters", JSONObject(dtlsParameters.toString()))
                put("rtpCapabilities", JSONObject(rtpCapabilties.toString()))

            })
        }

        protooClient.sendMessage(request.toString())
    }

    private fun initWebRTC() {
        eglBase = EglBase.create()
        binding.mySV.run {
            setMirror(true)
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }

        initPeerConnectionFactory(application)

        peerConnectionFactory = buildPeerConnectionFactory()
//        binding.mySV.setMirror(true)
//        binding.mySV.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
//        binding.mySV.setEnableHardwareScaler(true)
//        binding.mySV.init(eglBase.eglBaseContext, null)
//        setupRemoteView(this)
//        PeerConnectionFactory.initialize(
//            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
//        )
//
//        peerConnectionFactory = PeerConnectionFactory.builder()
//            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
//            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
//            .setAudioDeviceModule(
//                JavaAudioDeviceModule
//                    .builder(this)
//                    .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
//                    .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
//                    .createAudioDeviceModule().also {
//
//                    }
//            )
//            .createPeerConnectionFactory()
    }


    private fun buildPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory
            .builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setAudioDeviceModule(
                JavaAudioDeviceModule
                    .builder(this)
                    .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    .createAudioDeviceModule().also {

                    }
            )
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = true
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()
    }

//    private fun buildPeerConnection(observer: PeerConnection.Observer) =
//        peerConnectionFactory.createPeerConnection(
//            iceServer,
//            observer
//        )

    private fun initPeerConnectionFactory(context: Application) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    fun createLocalStream(context: Context) {
        // Initialize WebRTC
//        PeerConnectionFactory.initialize(
//            PeerConnectionFactory.InitializationOptions.builder(context)
//                .setEnableInternalTracer(true)
//                .createInitializationOptions()
//        )
//
//        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        // Create Video Capturer
        videoCapturer = createCameraCapturer(context)

        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast)
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())

        videoCapturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            context, videoSource.capturerObserver
        )
        videoCapturer.startCapture(320, 240, 60)

        // Create Video and Audio Tracks
        localVideoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audioTrack", audioSource)
        runOnUiThread {
            localVideoTrack.addSink(binding.mySV)
        }
        val localStream = peerConnectionFactory.createLocalMediaStream("local_steam")
        localStream.addTrack(localVideoTrack)
        localStream.addTrack(localAudioTrack)
//        peerConnection?.addStream(localStream)
    }

    // Create Camera Capturer
    private fun createCameraCapturer(context: Context): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        return Camera2Capturer(
            context,
            cameraName ?: throw RuntimeException("No front camera found"),
            null
        )
    }

    fun createSendTransport(res: String) {
        try {
            val info: JSONObject = JSONObject(res)
            Log.e("Paramester ", "createSendTransport $info")
            icePaCandData = res
            val id = info.get("id")
            val iceParameters = info.getJSONObject("iceParameters")
            val iceCandidates = info.getJSONArray("iceCandidates")
            val dtlsParameters = info.getJSONObject("dtlsParameters")
            val sctpParameters = info.getJSONObject("sctpParameters")
            Log.e(
                "Paramester ",
                "iceParameters $iceParameters iceCandidates $iceCandidates dtlsParameters $dtlsParameters sctpParameters $sctpParameters"
            )
            CoroutineScope(Dispatchers.IO).launch {
                val mSendTransport: SendTransport =
                    mMediasoupDevice.createSendTransport(
                        object : SendTransport.Listener {
                            override fun onConnect(transport: Transport?, dtlsParameters: String?) {
                                val request = JSONObject().apply {
                                    put("request", true)
                                    put("method", "connectWebRtcTransport")
                                    put("id", 2)
                                    put("data", JSONObject().apply {
                                        put("transportId", transport?.id)
                                        put("dtlsParameters", JSONObject(dtlsParameters))

                                    })

                                }
                                protooClient.sendMessage(request.toString())
                            }

                            override fun onConnectionStateChange(
                                transport: Transport?,
                                connectionState: String?
                            ) {
                                Log.d(
                                    "Mediasoup",
                                    "Transport connection state changed: $connectionState"
                                )
                            }

                            override fun onProduce(
                                transport: Transport?,
                                kind: String?,
                                rtpParameters: String?,
                                appData: String?
                            ): String {
                                val request = JSONObject().apply {
                                    put("request", true)
                                    put("method", "produce")
                                    put("id", 3)
                                    put("data", JSONObject().apply {
                                        put("transportId", transport?.id)
                                        put("kind", kind)

                                        put("rtpParameters", JSONObject(rtpParameters))
                                        put("appData", appData)


                                    })
                                }
                                protooClient.sendMessage(request.toString())
                                return ""
                            }

                            override fun onProduceData(
                                transport: Transport?,
                                sctpStreamParameters: String?,
                                label: String?,
                                protocol: String?,
                                appData: String?
                            ): String {
                                TODO("Not yet implemented")
                            }
                        },
                        id.toString(),
                        iceParameters.toString(),
                        iceCandidates.toString(),
                        dtlsParameters.toString()
                    )
                val mCamProducer =
                    mSendTransport.produce(
                        { producer: Producer? ->
//                Logger.e(TAG, "onTransportClose(), camProducer")
                        },
                        localVideoTrack, null, null, null
                    )
                val mAudioProducer =
                    mSendTransport.produce(
                        { producer: Producer? ->
//                Logger.e(TAG, "onTransportClose(), camProducer")
                        },
                        localAudioTrack, null, null, null
                    )
                mAudioProducer.pause()
            }


        } catch (e: Exception) {
            Log.e("error", e.toString())
        }

    }

    private lateinit var recvTransport: RecvTransport
    fun createRecvTransport() {
        val info: JSONObject = JSONObject(icePaCandData)
        val id = info.get("id")

        val iceParameters = info.getJSONObject("iceParameters")
        val iceCandidates = info.getJSONArray("iceCandidates")
        val dtlsParameters = info.getJSONObject("dtlsParameters")
        val sctpParameters = info.getJSONObject("sctpParameters")
        recvTransport = mMediasoupDevice.createRecvTransport(
            object : RecvTransport.Listener {
                override fun onConnect(transport: Transport, dtlsParameters: String) {
                    val request = JSONObject().apply {
                        put("request", true)
                        put("method", "connectWebRtcTransport")
                        put("id", connectWebRtcEvent)
                        put("data", JSONObject().apply {
                            put("transportId", transport.id)

                            put("dtlsParameters", JSONObject(dtlsParameters))


                        })
                    }
                    protooClient.sendMessage(request.toString())
                }

                override fun onConnectionStateChange(
                    transport: Transport,
                    connectionState: String
                ) {
                    Log.d("Mediasoup", "RecvTransport connection state changed: $connectionState")
                }
            },
            id.toString(),
            iceParameters.toString(),
            iceCandidates.toString(),
            dtlsParameters.toString(),
            sctpParameters.toString()
        )
    }

    fun createWebRtcTransport() {

        protooClient.sendMessage(JSONObject().apply {
            put("request", true)
            put("id", webRtcTransport)
            put("method", "createWebRtcTransport")
            put(
                "data",
                JSONObject().apply {
                    put("forceTcp", false)
                    put("producing", true)
                    put("consuming", true)
                    put("sctpCapabilities", JSONObject().apply {
                        put("numStreams", JSONObject().apply {
                            put("OS", 1024)
                            put("MIS", 1024)
                        })
                    })
                })
        }.toString())
    }

    fun joinRoom() {
//        {
//            "request":true, "id":5718720, "method":"join", "data":{
//            "displayName":"Glaceon", "device":{ "flag":"chrome", "name":"Chrome", "version":"133.0.0.0" }, "rtpCapabilities":{
//            "codecs":[{ "mimeType":"audio/opus", "kind":"audio", "preferredPayloadType":100, "clockRate":48000, "channels":2, "parameters":{ "minptime":10, "useinbandfec":1 }, "rtcpFeedback":[{ "type":"transport-cc", "parameter":"" },{ "type":"nack", "parameter":"" }] },{ "mimeType":"video/VP8", "kind":"video", "preferredPayloadType":101, "clockRate":90000, "parameters":{}, "rtcpFeedback":[{ "type":"goog-remb", "parameter":"" },{ "type":"transport-cc", "parameter":"" },{ "type":"ccm", "parameter":"fir" },{ "type":"nack", "parameter":"" },{ "type":"nack", "parameter":"pli" }] },{ "mimeType":"video/rtx", "kind":"video", "preferredPayloadType":102, "clockRate":90000, "parameters":{ "apt":101 }, "rtcpFeedback":[] },{ "mimeType":"video/VP9", "kind":"video", "preferredPayloadType":103, "clockRate":90000, "parameters":{ "profile-id":2 }, "rtcpFeedback":[{ "type":"goog-remb", "parameter":"" },{ "type":"transport-cc", "parameter":"" },{ "type":"ccm", "parameter":"fir" },{ "type":"nack", "parameter":"" },{ "type":"nack", "parameter":"pli" }] },{ "mimeType":"video/rtx", "kind":"video", "preferredPayloadType":104, "clockRate":90000, "parameters":{ "apt":103 }, "rtcpFeedback":[] },{ "mimeType":"video/H264", "kind":"video", "preferredPayloadType":105, "clockRate":90000, "parameters":{ "level-asymmetry-allowed":1, "packetization-mode":1, "profile-level-id":"4d001f" }, "rtcpFeedback":[{ "type":"goog-remb", "parameter":"" },{ "type":"transport-cc", "parameter":"" },{ "type":"ccm", "parameter":"fir" },{ "type":"nack", "parameter":"" },{ "type":"nack", "parameter":"pli" }] },{ "mimeType":"video/rtx", "kind":"video", "preferredPayloadType":106, "clockRate":90000, "parameters":{ "apt":105 }, "rtcpFeedback":[] },{ "mimeType":"video/H264", "kind":"video", "preferredPayloadType":107, "clockRate":90000, "parameters":{ "level-asymmetry-allowed":1, "packetization-mode":1, "profile-level-id":"42e01f" }, "rtcpFeedback":[{ "type":"goog-remb", "parameter":"" },{ "type":"transport-cc", "parameter":"" },{ "type":"ccm", "parameter":"fir" },{ "type":"nack", "parameter":"" },{ "type":"nack", "parameter":"pli" }] },{ "mimeType":"video/rtx", "kind":"video", "preferredPayloadType":108, "clockRate":90000, "parameters":{ "apt":107 }, "rtcpFeedback":[] }], "headerExtensions":[{ "kind":"audio", "uri":"urn:ietf:params:rtp-hdrext:sdes:mid", "preferredId":1, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"video", "uri":"urn:ietf:params:rtp-hdrext:sdes:mid", "preferredId":1, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"audio", "uri":"http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time", "preferredId":4, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"video", "uri":"http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time", "preferredId":4, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"video", "uri":"http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01", "preferredId":5, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"audio", "uri":"urn:ietf:params:rtp-hdrext:ssrc-audio-level", "preferredId":10, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"video", "uri":"urn:3gpp:video-orientation", "preferredId":11, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"video", "uri":"urn:ietf:params:rtp-hdrext:toffset", "preferredId":12, "preferredEncrypt":false, "direction":"sendrecv" },{ "kind":"video", "uri":"http://www.webrtc.org/experiments/rtp-hdrext/playout-delay", "preferredId":14, "preferredEncrypt":false, "direction":"sendrecv" }]
//        }, "sctpCapabilities":{ "numStreams":{ "OS":1024, "MIS":1024 } }
//        }
//        }
        protooClient.sendMessage(JSONObject().apply {
            put("request", true)
            put("id", joinEvent)
            put("method", "join")
            put(
                "data",
                JSONObject().apply {
                    put("displayName", "Randeep")
                    put("device", JSONObject().apply {
                        put("flag", "Android")
                        put("name", "Android")
                        put("version", "1.928")
                    })
                    put("rtpCapabilities", rtpCapabilties)
                    put("sctpCapabilities", JSONObject().apply {
                        put("numStreams", JSONObject().apply {
                            put("OS", 1024)
                            put("MIS", 1024)
                        })
                    })
                })
        }.toString())
    }

    fun initMediaSoup() {
// ...
// routerRtpCapabilities, the response of request `getRouterRtpCapabilities` from mediasoup-demo server

    }
}

class ProtooClient(serverUri: URI) :
    WebSocketClient(
        serverUri, Draft_6455(), mapOf(
            "Sec-WebSocket-Protocol" to "protoo",
            "Sec-WebSocket-Version" to "13",
            "Connection" to "Upgrade",
            "Upgrade" to "websocket",
            "Sec-WebSocket-Extensions" to "permessage-deflate; client_max_window_bits"
        )
    ) {
    var onMessageHandler: (String.() -> Unit)? = null
    var onOpen: (String.() -> Unit)? = null
    override fun onOpen(handshakedata: ServerHandshake?) {
        println("✅ Connected to Protoo Server with protocol: " + handshakedata?.httpStatusMessage)
        onOpen?.let { onOpen?.invoke("") }
        // Send a JOIN request

//        send(requestMessage)
    }

    override fun onMessage(message: String?) {
        println("📨 Received message: $message")
        message?.let { onMessageHandler?.invoke(it) }

    }

    override fun onMessage(bytes: ByteBuffer?) {
        super.onMessage(bytes)
        println("📨 Received bytes message: $bytes")
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        println("❌ Connection closed: $reason")
    }

    override fun onError(ex: Exception?) {
        println("⚠ Error: ${ex?.message}")
        ex?.printStackTrace()
    }

    fun sendMessage(message: String) {
        try {
            val jsonObject = JSONObject(message)
            send(jsonObject.toString())
            Log.d("Protoo", "Sent message: $jsonObject")
        } catch (e: JSONException) {
            Log.e("Protoo", "Invalid JSON format", e)
        }
    }

}