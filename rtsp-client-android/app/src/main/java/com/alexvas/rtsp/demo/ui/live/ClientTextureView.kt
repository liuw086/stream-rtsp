package com.alexvas.rtsp.demo.ui.live

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import com.alexvas.rtsp.RtspClient
import com.alexvas.rtsp.demo.decode.FrameQueue
import com.alexvas.rtsp.demo.decode.VideoDecodeThread
import com.alexvas.utils.NetUtils
import com.alexvas.utils.VideoCodecUtils
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_RTSP_PORT = 1935
private val TAG: String = ClientTextureView::class.java.simpleName
private const val DEBUG = true

class ClientTextureView(context: Context, attrs: AttributeSet?) : TextureView(context, attrs),
    TextureView.SurfaceTextureListener , SurfaceHolder.Callback{

    private var videoFrameQueue: FrameQueue = FrameQueue()
    private var audioFrameQueue: FrameQueue = FrameQueue()
    private var rtspThread: RtspThread? = null
    private var videoDecodeThread: VideoDecodeThread? = null
    private var rtspStopped: AtomicBoolean = AtomicBoolean(true)

    private var surface: Surface? = null
    private var surfaceWidth: Int = 720
    private var surfaceHeight: Int = 1280

    private var videoMimeType: String = "video/avc"
    val CENTER_CROP_MODE = 1 //中心裁剪模式

    val CENTER_MODE = 2 //一边中心填充模式

    init {
        surfaceTextureListener = this
    }

    fun onRtspClientStarted() {
        if (DEBUG) Log.v(TAG, "onRtspClientStarted()")
        rtspStopped.set(false)
    }

    fun onRtspClientStopped() {
        if (DEBUG) Log.v(TAG, "onRtspClientStopped()")
        rtspStopped.set(true)
        videoDecodeThread?.interrupt()
        videoDecodeThread = null
    }

    fun onRtspClientConnected() {
        if (DEBUG) Log.v(TAG, "onRtspClientConnected()")
        if (videoMimeType.isNotEmpty() ) {
            Log.i(TAG, "Starting video decoder with mime type \"$videoMimeType\"")
            videoDecodeThread = VideoDecodeThread(surface!!, videoMimeType, surfaceWidth, surfaceHeight, videoFrameQueue)
            videoDecodeThread?.start()
//            updateTextureViewSize(CENTER_CROP_MODE)
        }
    }

    inner class RtspThread: Thread() {
        override fun run() {
            Handler(Looper.getMainLooper()).post { onRtspClientStarted() }
            val listener = object: RtspClient.RtspClientListener {
                override fun onRtspDisconnected() {
                    if (DEBUG) Log.v(TAG, "onRtspDisconnected()")
                    rtspStopped.set(true)
                }

                override fun onRtspFailed(message: String?) {
                    Log.e(TAG, "onRtspFailed(message=\"$message\")")
                    rtspStopped.set(true)
                }

                override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                    if (DEBUG) Log.v(TAG, "onRtspConnected()")
                    Handler(Looper.getMainLooper()).post {
                        var s = ""
                        if (sdpInfo.videoTrack != null)
                            s = "video"
                        if (sdpInfo.audioTrack != null) {
                            if (s.length > 0)
                                s += ", "
                            s += "audio"
                        }
                    }
                    if (sdpInfo.videoTrack != null) {
                        videoFrameQueue.clear()
                        when (sdpInfo.videoTrack?.videoCodec) {
                            RtspClient.VIDEO_CODEC_H264 -> videoMimeType = "video/avc"
                            RtspClient.VIDEO_CODEC_H265 -> videoMimeType = "video/hevc"
                        }
                        val sps: ByteArray? = sdpInfo.videoTrack?.sps
                        val pps: ByteArray? = sdpInfo.videoTrack?.pps
                        // Initialize decoder
                        if (sps != null && pps != null) {
                            val data = ByteArray(sps.size + pps.size)
                            sps.copyInto(data, 0, 0, sps.size)
                            pps.copyInto(data, sps.size, 0, pps.size)
                            videoFrameQueue.push(FrameQueue.Frame(data, 0, data.size, 0))
                        } else {
                            if (DEBUG) Log.d(TAG, "RTSP SPS and PPS NAL units missed in SDP")
                        }
                    }
                    onRtspClientConnected()
                }

                override fun onRtspFailedUnauthorized() {
                    Log.e(TAG, "onRtspFailedUnauthorized()")
                    rtspStopped.set(true)
                }

                override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (length > 0)
                        videoFrameQueue.push(FrameQueue.Frame(data, offset, length, timestamp))

                    if (DEBUG) {
                        val nals: ArrayList<VideoCodecUtils.NalUnit> = ArrayList()
                        val numNals = VideoCodecUtils.getH264NalUnits(data, offset, length - 1, nals)
                        val builder = StringBuilder()
                        for (nal in nals) {
                            builder.append(", ")
                            builder.append(VideoCodecUtils.getH264NalUnitTypeString(nal.type))
                            builder.append(" (")
                            builder.append(nal.length)
                            builder.append(" bytes)")
                        }
                        var textNals = builder.toString()
                        if (numNals > 0) {
                            textNals = textNals.substring(2)
                        }
                        Log.v(TAG, "onRtspVideoNalUnitReceived(length=$length, timestamp=$timestamp),videoFrameQueue=${videoFrameQueue.queue.size} NALs ($numNals): $textNals")
                    }

                }

                override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
                    if (DEBUG) Log.v(TAG, "onRtspAudioSampleReceived(length=$length, timestamp=$timestamp)")
                    if (length > 0)
                        audioFrameQueue.push(FrameQueue.Frame(data, offset, length, timestamp))
                }

                override fun onRtspConnecting() {
                    if (DEBUG) Log.v(TAG, "onRtspConnecting()")
                }
            }
            val DEFAULT_RTSP_REQUEST = "rtsp://10.100.6.9:1935/" //"rtsp://192.168.176.220:1935/" //"rtsp://10.100.6.1:1935/" //
            val uri: Uri = Uri.parse(DEFAULT_RTSP_REQUEST)
            val port = if (uri.port == -1) DEFAULT_RTSP_PORT else uri.port
            try {
                if (DEBUG) Log.d(TAG, "Connecting to ${uri.host.toString()}:$port...")

                val socket: Socket = NetUtils.createSocketAndConnect(uri.host.toString(), port, 5000)

                // Blocking call until stopped variable is true or connection failed
                val rtspClient = RtspClient.Builder(socket, uri.toString(), rtspStopped, listener)
                    .requestVideo(true)
                    .requestAudio(false)
                    .withDebug(true)
                    .withUserAgent("RTSP test")
                    .build()
                rtspClient.execute()

                NetUtils.closeSocket(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Handler(Looper.getMainLooper()).post { onRtspClientStopped() }
        }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (DEBUG) Log.v(TAG, "surfaceChanged(, width=$width, height=$height)")
        this.surface = Surface(surface);
        if (DEBUG) Log.d(TAG, "Starting RTSP thread...")
        rtspThread = RtspThread()
        rtspThread?.start()

    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (DEBUG) Log.d(TAG, "onSurfaceTextureSizeChanged")

    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (DEBUG) Log.d(TAG, "onSurfaceTextureDestroyed")
        return true;
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
//        if (DEBUG) Log.d(TAG, "onSurfaceTextureUpdated")
    }


    /**
     * @param mode Pass [.CENTER_CROP_MODE] or [.CENTER_MODE]. Default
     * value is 0.
     */
    fun updateTextureViewSize(mode: Int) {
        if (mode == CENTER_MODE) {
            updateTextureViewSizeCenter()
        } else if (mode == CENTER_CROP_MODE) {
            updateTextureViewSizeCenterCrop()
        }
    }
    private var mVideoWidth: Int = 1920
    private var mVideoHeight: Int = 1080
    //重新计算video的显示位置，裁剪后全屏显示
    private fun updateTextureViewSizeCenterCrop() {
        val sx = width.toFloat() / mVideoWidth.toFloat()
        val sy = height.toFloat() / mVideoHeight.toFloat()
        val matrix = Matrix()
        val maxScale = Math.max(sx, sy)

        //第1步:把视频区移动到View区,使两者中心点重合.
        matrix.preTranslate(
            ((width - mVideoWidth) / 2).toFloat(),
            ((height - mVideoHeight) / 2).toFloat()
        )

        //第2步:因为默认视频是fitXY的形式显示的,所以首先要缩放还原回来.
        matrix.preScale(mVideoWidth / width.toFloat(), mVideoHeight / height.toFloat())

        //第3步,等比例放大或缩小,直到视频区的一边超过View一边, 另一边与View的另一边相等. 因为超过的部分超出了View的范围,所以是不会显示的,相当于裁剪了.
        matrix.postScale(
            maxScale,
            maxScale,
            (width / 2).toFloat(),
            (height / 2).toFloat()
        ) //后两个参数坐标是以整个View的坐标系以参考的
        setTransform(matrix)
        postInvalidate()
    }

    //重新计算video的显示位置，让其全部显示并据中
    private fun updateTextureViewSizeCenter() {
        val sx = width.toFloat() / mVideoWidth.toFloat()
        val sy = height.toFloat() / mVideoHeight.toFloat()
        val matrix = Matrix()

        //第1步:把视频区移动到View区,使两者中心点重合.
        matrix.preTranslate(
            ((width - mVideoWidth) / 2).toFloat(),
            ((height - mVideoHeight) / 2).toFloat()
        )

        //第2步:因为默认视频是fitXY的形式显示的,所以首先要缩放还原回来.
        matrix.preScale(mVideoWidth / width.toFloat(), mVideoHeight / height.toFloat())

        //第3步,等比例放大或缩小,直到视频区的一边和View一边相等.如果另一边和view的一边不相等，则留下空隙
        if (sx >= sy) {
            matrix.postScale(sy, sy, (width / 2).toFloat(), (height / 2).toFloat())
        } else {
            matrix.postScale(sx, sx, (width / 2).toFloat(), (height / 2).toFloat())
        }
        setTransform(matrix)
        postInvalidate()
    }


    // SurfaceHolder.Callback
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (DEBUG) Log.v(TAG, "surfaceChanged(format=$format, width=$width, height=$height)")
        surface = holder.surface
//        surfaceWidth = width
//        surfaceHeight = height
        if (videoDecodeThread != null) {
            videoDecodeThread?.interrupt()
            videoDecodeThread = VideoDecodeThread(surface!!, videoMimeType, surfaceWidth, surfaceHeight, videoFrameQueue)
            videoDecodeThread?.start()
        }
    }

    // SurfaceHolder.Callback
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (DEBUG) Log.v(TAG, "surfaceDestroyed()")
        videoDecodeThread?.interrupt()
        videoDecodeThread = null
    }

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (DEBUG) Log.v(TAG, "surfaceCreated()")
    }
}