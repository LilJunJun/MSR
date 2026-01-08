package com.nramos.msr

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.provider.MediaStore
import androidx.core.content.getSystemService
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.FileInputStream

//to help with communication for the service
@Parcelize
data class ScreenRecordConfig(
    val resultCode: Int, //result code for when we successfully start recording post dialog
    val data: Intent //the actual data of that screen recording action
): Parcelable //we parcelize this because we want to be able to pass this from activity to the service via bundle

class RecordService : Service() {

    //what projects our screen's contents on like a so called virtual monitor that can be recorded
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    //the controller
    private val mediaRecorder by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            MediaRecorder()
        }
    }

    //launch coroutines in that service, that is bound to the lifetime of that service
    //so when it comes time to save the file we dont accidentally block the main thread because the service is running on the main thread by default
    //saving file could quickly make the app drop frames since that is an expensive operation
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val mediaProjectionManager by lazy {
        getSystemService<MediaProjectionManager>()
    }

    private val outputFile by lazy {
        File(cacheDir, "tmp.mp4")
        //temporary file which we will then save in the gallery
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_RECORDING -> {
                val notification = NotificationHelper.createNotification(applicationContext)
                NotificationHelper.createNotificationChannel(applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        1, //must be 1 at least
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(
                        1,
                        notification
                    )
                }
                _isServiceRunning.value = true
                startRecording(intent) //we also pass the intent here so we can receive the data from it
            }

            STOP_RECORDING -> {
                stopRecording() //will only be done if the user explicitly stops based on our ui button
            }
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        //extract the screen recording from the intent
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                KEY_RECORDING_CONFIG,
                ScreenRecordConfig::class.java
            )
        } else {
            intent.getParcelableExtra(KEY_RECORDING_CONFIG,)
        }

        if (config == null) {
            return //in case we get an invalid intent, nothing we can do
        }

        //initialize a media projection with media projection manager which is the system service
        mediaProjection = mediaProjectionManager?.getMediaProjection(
            config.resultCode,
            config.data
        )
        mediaProjection?.registerCallback(mediaProjectionCallback, null)

        initializeRecorder()
        mediaRecorder.start()

        virtualDisplay = createVirtualDisplay()
    }

    private fun stopRecording() {
        mediaRecorder.stop()
        mediaProjection?.stop()
        mediaRecorder.reset()
    }

    private fun stopService() {
        _isServiceRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE) //remove the notification
        stopSelf() //stop the service
    }

    //get the screen size
    private fun getWindowSize(): Pair<Int, Int> {
        val calculator = WindowMetricsCalculator.getOrCreate()
        val metrics =
            calculator.computeMaximumWindowMetrics(applicationContext) //max size and max dimensions the window will ever take - we want to include the top system information ie: time
        return metrics.bounds.width() to metrics.bounds.height()
    }

    //to prevent crash for lower api levels ~28 since the above would sometimes crash with an invalid value
    private fun getScaledDimensions(
        maxWidth: Int,
        maxHeight: Int,
        scaleFactor: Float = 0.8f //scale down by 80%
    ): Pair<Int, Int> {
        val aspectRatio =
            maxWidth / maxHeight.toFloat() //aspect ratio is always width divided by height

        var newWidth = (maxWidth * scaleFactor).toInt()
        var newHeight = (newWidth / aspectRatio).toInt()

        //if the height is larger than the original height times 80% then we need to scale down
        if (newHeight > (maxHeight * scaleFactor)) {
            newHeight = (maxHeight * scaleFactor).toInt()
            newWidth = (newHeight * aspectRatio).toInt()
        }

        return newWidth to newHeight
    }

    private fun initializeRecorder() {
        val (width, height) = getWindowSize()
        val (scaledWidth, scaledHeight) = getScaledDimensions(
            maxWidth = width,
            maxHeight = height
        )//final dimensions scaled down by 80%

        with(mediaRecorder) {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP) //three gpp is best for media compatibility
            setOutputFile(outputFile)
            setVideoSize(scaledWidth, scaledHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(VIDEO_BIT_RATE_KILOBITS * 1000)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            prepare() //used in order to prepare the media recorder - if anything above is wrong it will say prepare failed
        }

    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        val (width, height) = getWindowSize()
        return mediaProjection?.createVirtualDisplay(
            "Screen",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        )
    }

    //callback that will trigger when resources of this recording need to be freed up
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            //this will be called regardless of how or why it stopped
            releaseResources()
            stopService()
            saveToGallery()
        }
    }

    private fun saveToGallery() {
        //save on a background thread
        serviceScope.launch {
            //the metadata of the file we want to save in our galley
            val contentValues = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    "SRvideo_${System.currentTimeMillis()}.mp4"
                )
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecordings")
            }
            //uri pointing to the video collection of our movies directory in external storage
            val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                //mediastore is the big global db, the big content provider for all things media related metadata
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            contentResolver.insert(videoCollection, contentValues)
                ?.let { uri -> //send files or bytes out to mediastore by passing the uri
                    //use lets us close the stream without having to explicitly do so
                    contentResolver.openOutputStream(uri)
                        ?.use { outputStream ->  //stream where we want to write our actual file contents to
                            FileInputStream(outputFile).use { inputStream -> //get the bytes and copy it to our outputstream which is then pointing to mediastore
                                inputStream.copyTo(outputStream)
                            }
                        }
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        serviceScope.coroutineContext.cancelChildren() //we use cancel children because we dont want to cancel the scope itself - otherwise we wont be able to relaunch the service
    }

    //release everything so we dont have any memory leaks
    private fun releaseResources() {
        mediaRecorder.release()
        virtualDisplay?.release()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection = null
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_BIT_RATE_KILOBITS =
            512 //the larger this value is the better the video quality would be

        //some public values we can use to send as intents to control the service
        const val START_RECORDING = "START_RECORDING"
        const val STOP_RECORDING = "STOP_RECORDING"
        const val KEY_RECORDING_CONFIG =
            "KEY_RECORDING_CONFIG" //key we'll actually use to pass from in the activity's bundle to the service
    }
}
