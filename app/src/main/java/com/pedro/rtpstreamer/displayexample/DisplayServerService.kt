/*
 * Copyright (C) 2021 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtpstreamer.displayexample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel31
import android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel5
import android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileMain
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.rtplibrary.base.DisplayBase
import com.pedro.rtplibrary.rtmp.RtmpDisplay
import com.pedro.rtplibrary.rtsp.RtspDisplay
import com.pedro.rtpstreamer.R
import com.pedro.rtpstreamer.backgroundexample.ConnectCheckerRtp
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.rtspserver.RtspServerDisplay


/**
 * Basic RTMP/RTSP service streaming implementation with camera2
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class DisplayServerService : Service() {

  private var endpoint: String? = null

  override fun onCreate() {
    super.onCreate()
    Log.e(TAG, "RTP Display service create")
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH)
      notificationManager?.createNotificationChannel(channel)
    }
    keepAliveTrick()
  }

  private fun keepAliveTrick() {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
      val notification = NotificationCompat.Builder(this, channelId)
          .setOngoing(true)
          .setContentTitle("")
          .setContentText("").build()
      startForeground(1, notification)
    } else {
      startForeground(1, Notification())
    }
  }

  override fun onBind(p0: Intent?): IBinder? {
    return null
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.e(TAG, "RTP Display service started")
    endpoint = intent?.extras?.getString("endpoint")
    if (endpoint != null) {
      prepareStreamRtp()
      startStreamRtp(endpoint!!)
    }
    return START_STICKY
  }

  companion object {
    private const val TAG = "DisplayService"
    private const val channelId = "rtpDisplayStreamChannel"
    private const val notifyId = 123456
    private var notificationManager: NotificationManager? = null
    private var displayBase: DisplayBase? = null
    private var contextApp: Context? = null
    private var resultCode: Int? = null
    private var data: Intent? = null
    private const val port: Int = 1935

    fun init(context: Context) {
      contextApp = context
      if (displayBase == null) displayBase = RtspServerDisplay(context, false, connectCheckerRtp, port)
    }

    fun setData(resultCode: Int, data: Intent) {
      this.resultCode = resultCode
      this.data = data
    }

    fun sendIntent(): Intent? {
      if (displayBase != null) {
        return displayBase!!.sendIntent()
      } else {
        return null
      }
    }

    fun isStreaming(): Boolean {
      return if (displayBase != null) displayBase!!.isStreaming else false
    }

    fun isRecording(): Boolean {
      return if (displayBase != null) displayBase!!.isRecording else false
    }

    fun stopStream() {
      if (displayBase != null) {
        if (displayBase!!.isStreaming) displayBase!!.stopStream()
      }
    }

    private val connectCheckerRtp = object : ConnectCheckerRtsp {

      override fun onConnectionStartedRtsp(rtpUrl: String) {
        Log.e(TAG, "RTP connection started")
      }

      override fun onConnectionSuccessRtsp() {
        showNotification("Stream started")
        if (displayBase != null) {
          if (displayBase!!.isStreaming) displayBase!!.requestKeyFrame()
        }
        Log.e(TAG, "RTP connection Success")
      }

      override fun onNewBitrateRtsp(bitrate: Long) {

      }

      override fun onConnectionFailedRtsp(reason: String) {
        showNotification("Stream connection failed")
        Log.e(TAG, "RTP service destroy")
      }

      override fun onDisconnectRtsp() {
        showNotification("Stream stopped")
      }

      override fun onAuthErrorRtsp() {
        showNotification("Stream auth error")
      }

      override fun onAuthSuccessRtsp() {
        showNotification("Stream auth success")
      }
    }

    private fun showNotification(text: String) {
      contextApp?.let {
        val notification = NotificationCompat.Builder(it, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RTP Display Stream")
            .setContentText(text).build()
        notificationManager?.notify(notifyId, notification)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.e(TAG, "RTP Display service destroy")
    stopStream()
  }

  private fun prepareStreamRtp() {
    stopStream()
    displayBase = RtspServerDisplay(baseContext, true, connectCheckerRtp, port)
    displayBase?.setIntentResult(resultCode!!, data)
  }

  private fun startStreamRtp(endpoint: String) {
    if (!displayBase!!.isStreaming) {
      if (displayBase!!.prepareVideo(1280, 720, 15, 10 * 1024 * 1024,
          90, 440, AVCProfileHigh, AVCLevel5, 0)) {
//        && displayBase!!.prepareAudio()) {
        displayBase!!.startStream(endpoint)
      }
    } else {
      showNotification("You are already streaming :(")
    }
  }
}
