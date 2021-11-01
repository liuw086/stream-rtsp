package com.alexvas.rtsp.demo.decode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.lang.Exception
import java.nio.ByteBuffer

class VideoDecodeThread(
    private val surface: Surface,
    private val mimeType: String,
    private val width: Int,
    private val height: Int,
    private val videoFrameQueue: FrameQueue
) : Thread() {

    private val TAG: String = VideoDecodeThread::class.java.simpleName
    private val DEBUG = true

    override fun run() {
        if (DEBUG) Log.d(TAG, "VideoDecodeThread started $mimeType, $width X $height")

        val decoder = MediaCodec.createDecoderByType(mimeType)
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        decoder.configure(format, surface, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        while (!interrupted()) {

            val frame: FrameQueue.Frame?
            try {
                frame = videoFrameQueue.pop()
                if (frame == null) {
                    Log.d(TAG, "Empty frame")
                } else {
                    offerDecoder(frame.data, frame.offset, frame.length, decoder)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // All decoded frames have been rendered, we can stop playing now
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                break
            }
        }

        decoder.stop()
        decoder.release()
        videoFrameQueue.clear()

        if (DEBUG) Log.d(TAG, "VideoDecodeThread stopped")
    }

    //解码h264数据
    private fun offerDecoder(input: ByteArray, offset: Int, length: Int, decoder: MediaCodec) {
        try {
            val inputBuffers: Array<ByteBuffer> = decoder.getInputBuffers()
            val inputBufferIndex: Int = decoder.dequeueInputBuffer(0)
            if (inputBufferIndex >= 0) {
                val inputBuffer = inputBuffers[inputBufferIndex]
                inputBuffer.clear()
                try {
                    inputBuffer.put(input, offset, length)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                decoder.queueInputBuffer(inputBufferIndex, 0, length, 0, 0)
            }
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex: Int = decoder.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                decoder.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (t: Exception) {
            t.printStackTrace()
        }
    }
}

