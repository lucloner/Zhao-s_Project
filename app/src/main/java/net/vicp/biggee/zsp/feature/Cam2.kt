package net.vicp.biggee.zsp.feature

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.baidu.aip.face.PreviewView
import com.baidu.aip.face.camera.ICameraControl
import com.baidu.aip.face.camera.PermissionCallback
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min

@TargetApi(Build.VERSION_CODES.N)
class Cam2 internal constructor(val context: Context) : ICameraControl<ByteArray>, CameraDevice.StateCallback(),
        TextureView.SurfaceTextureListener, ICameraControl.OnTakePictureCallback, ImageReader.OnImageAvailableListener, ThreadFactory {

    private var cameraFacing: Int = ICameraControl.CAMERA_FACING_FRONT
    private var width = 720
    private var height = 1280
    private lateinit var previewView: PreviewView
    var lensFocalLength = 0f
    private lateinit var textureView: TextureView
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface
    private var displayOrientation: Int = 0
    private var session: CameraCaptureSession? = null
    private var data: ByteArray? = null
    private var stoped = true
    private var previewFrame: Rect? = null
    @Volatile
    private var timenow = System.currentTimeMillis()
    @Volatile
    var timestart = timenow
    private var camera: CameraDevice? = null
    private val flashMode = CameraMetadata.FLASH_MODE_OFF
    private lateinit var listener: ICameraControl.OnFrameListener<Any>
    private var exe = Executors.newSingleThreadScheduledExecutor()
    @Volatile
    private var timestamp = timenow
    private var check = false
    private lateinit var imageReader: ImageReader
    private lateinit var callback: PermissionCallback
    var sdkOk: Boolean = false
    private var backgroundThread: HandlerThread? = null
    private var handler: Handler? = null
    val frameQueue: LinkedBlockingQueue<Runnable> by lazy { LinkedBlockingQueue<Runnable>(FPS / 2) }
    val framePool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
                1,
                1,
                1,
                TimeUnit.NANOSECONDS,
                frameQueue,
                this,
                ThreadPoolExecutor.DiscardOldestPolicy()
        )
    }

    companion object {
        const val FPS = 30
        private const val hardwareDelay = 1000
        private const val CAMDIE = 32768
        private const val logtag = "Z's P-C2-"
        const val REQUEST_CAMERA_PERMISSION = 1
        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        fun logOutput(who: String, what: String) {
            Log.v(logtag + who, what)
        }
    }

    /**
     * Constructs a new `Thread`.  Implementations may also initialize
     * priority, name, daemon status, `ThreadGroup`, etc.
     *
     * @param r a runnable to be executed by new thread instance
     * @return constructed thread, or `null` if the request to
     * create a thread is rejected
     */
    override fun newThread(r: Runnable?): Thread {
        return Thread(
                null,
                r,
                "frame${System.currentTimeMillis()}",
                0
        ).apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            isDaemon = true
        }.also {
            it.start()
        }
        handler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            handler?.removeCallbacksAndMessages(null)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }

    override fun setOnFrameListener(listener: ICameraControl.OnFrameListener<Any>) {
        this.listener = listener
    }

    /**
     * Callback that is called when a new image is available from ImageReader.
     *
     * @param reader the ImageReader the callback is associated with.
     * @see ImageReader
     *
     * @see android.media.Image
     */
    override fun onImageAvailable(reader: ImageReader?) {
        timestart = System.currentTimeMillis()
        framePool.execute {
            try {
                val image = reader?.acquireLatestImage() ?: return@execute

                //获取源数据，如果是YUV格式的数据planes.length = 3
                //plane[i]里面的实际数据可能存在byte[].length <= capacity (缓冲区总大小)
                val planes = image.planes

                //数据有效宽度，一般的，图片width <= rowStride，这也是导致byte[].length <= capacity的原因
                // 所以我们只取width部分
                val width = image.width
                val height = image.height

                //此处用来装填最终的YUV数据，需要1.5倍的图片大小，因为Y U V 比例为 4:1:1
                val yuvBytes = ByteArray((width * height * 1.5).toInt())
                //目标数组的装填到的位置
                var dstIndex = 0

                //临时存储uv数据的
//            val uBytes = ByteArray(width * height / 4)
//            val vBytes = ByteArray(width * height / 4)
//            var uIndex = 0
//            var vIndex = 0

                var pixelsStride: Int
                var rowStride: Int
                for (i in planes.indices) {
                    var index = 0

                    pixelsStride = planes[i].pixelStride
                    rowStride = planes[i].rowStride

                    val buffer = planes[i].buffer

                    //如果pixelsStride==2，一般的Y的buffer长度=640*480，UV的长度=640*480/2-1
                    //源数据的索引，y的数据是byte中连续的，u的数据是v向左移以为生成的，两者都是偶数位为有效数据
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)

                    var srcIndex = 0
                    if (i == 0) {
                        //直接取出来所有Y的有效区域，也可以存储成一个临时的bytes，到下一步再copy
                        for (j in 0 until height) {
                            System.arraycopy(bytes, srcIndex, yuvBytes, dstIndex, width)
                            srcIndex += rowStride
                            dstIndex += width
                        }
                    } else if (i == 1) {
                        //根据pixelsStride取相应的数据
                        for (j in 0 until height / 2) {
                            for (k in 0 until width / 2) {
                                yuvBytes[dstIndex + 1 + 2 * index++] = bytes[srcIndex]
                                srcIndex += pixelsStride
                            }
                            if (pixelsStride == 2) {
                                srcIndex += rowStride - width
                            } else if (pixelsStride == 1) {
                                srcIndex += rowStride - width / 2
                            }
                        }
                    } else if (i == 2) {
                        //根据pixelsStride取相应的数据
                        for (j in 0 until height / 2) {
                            for (k in 0 until width / 2) {
                                yuvBytes[dstIndex + 2 * index++] = bytes[srcIndex]
                                srcIndex += pixelsStride
                            }
                            if (pixelsStride == 2) {
                                srcIndex += rowStride - width
                            } else if (pixelsStride == 1) {
                                srcIndex += rowStride - width / 2
                            }
                        }
                    }
                }

                image.close()
                data = yuvBytes

                if (sdkOk && !stoped) {
                    listener.onPreviewFrame(yuvBytes.clone(), displayOrientation + 180, width, height)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * The method called when a camera device has finished opening.
     *
     *
     * At this point, the camera device is ready to use, and
     * [CameraDevice.createCaptureSession] can be called to set up the first capture
     * session.
     *
     * @param camera the camera device that has become opened
     */
    override fun onOpened(camera: CameraDevice) {
        this.camera = camera
        try {
            imageReader = ImageReader.newInstance(max(width, height), min(width, height), ImageFormat.YUV_420_888, FPS)

            var width = this.width
            var height = this.height
            if (displayOrientation % 180 == 90) {
                width = this.height
                height = this.width
            }

            previewFrame = Rect(0, 0, width, height)

            imageReader.setOnImageAvailableListener(this, handler)

            texture.setDefaultBufferSize(width, height)
            surface = Surface(texture)
            val mPreviewRequestBuilder: CaptureRequest.Builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            mPreviewRequestBuilder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            )
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCAL_LENGTH, lensFocalLength)
            mPreviewRequestBuilder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
//            mPreviewRequestBuilder.set<Array<MeteringRectangle>>(CaptureRequest.CONTROL_AWB_REGIONS, null)
            mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, flashMode)
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
            }
//            mPreviewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,90)

            mPreviewRequestBuilder.addTarget(surface)
            mPreviewRequestBuilder.addTarget(imageReader.surface)

            val captureRequest = mPreviewRequestBuilder.build()

            logOutput(
                    logtag,
                    "width:$width height:$height displayOrientation:$displayOrientation previewView.textureView.width:${previewView.textureView.width} previewView.textureView.height:${previewView.textureView.height}"
            )

            camera.createCaptureSession(
                    listOf(surface, imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                this@Cam2.session = session
                                session.setRepeatingRequest(captureRequest, CamDebuger(), handler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            checkAlive()
                        }
                    }, handler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * The method called when a camera device is no longer available for
     * use.
     *
     *
     * This callback may be called instead of [.onOpened]
     * if opening the camera fails.
     *
     *
     * Any attempt to call methods on this CameraDevice will throw a
     * [CameraAccessException]. The disconnection could be due to a
     * change in security policy or permissions; the physical disconnection
     * of a removable camera device; or the camera being needed for a
     * higher-priority camera API client.
     *
     *
     * There may still be capture callbacks that are invoked
     * after this method is called, or new image buffers that are delivered
     * to active outputs.
     *
     *
     * The default implementation logs a notice to the system log
     * about the disconnection.
     *
     *
     * You should clean up the camera with [CameraDevice.close] after
     * this happens, as it is not recoverable until the camera can be opened
     * again. For most use cases, this will be when the camera again becomes
     * [available][CameraManager.AvailabilityCallback.onCameraAvailable].
     *
     *
     * @param camera the device that has been disconnected
     */
    override fun onDisconnected(camera: CameraDevice) {
        stop()
    }

    /**
     * The method called when a camera device has encountered a serious error.
     *
     *
     * This callback may be called instead of [.onOpened]
     * if opening the camera fails.
     *
     *
     * This indicates a failure of the camera device or camera service in
     * some way. Any attempt to call methods on this CameraDevice in the
     * future will throw a [CameraAccessException] with the
     * [CAMERA_ERROR][CameraAccessException.CAMERA_ERROR] reason.
     *
     *
     *
     * There may still be capture completion or camera stream callbacks
     * that will be called after this error is received.
     *
     *
     * You should clean up the camera with [CameraDevice.close] after
     * this happens. Further attempts at recovery are error-code specific.
     *
     * @param camera The device reporting the error
     * @param error The error code.
     *
     * @see .ERROR_CAMERA_IN_USE
     *
     * @see .ERROR_MAX_CAMERAS_IN_USE
     *
     * @see .ERROR_CAMERA_DISABLED
     *
     * @see .ERROR_CAMERA_DEVICE
     *
     * @see .ERROR_CAMERA_SERVICE
     */
    override fun onError(camera: CameraDevice, error: Int) {
        checkAlive()
    }

    /**
     * Invoked when the [SurfaceTexture]'s buffers size changed.
     *
     * @param surface The surface returned by
     * [android.view.TextureView.getSurfaceTexture]
     * @param width The new width of the surface
     * @param height The new height of the surface
     */
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
        checkAlive()
    }

    /**
     * Invoked when the specified [SurfaceTexture] is updated through
     * [SurfaceTexture.updateTexImage].
     *
     * @param surface The surface just updated
     */
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        timenow = System.currentTimeMillis()
//        texture = surface
    }

    /**
     * Invoked when the specified [SurfaceTexture] is about to be destroyed.
     * If returns true, no rendering should happen inside the surface texture after this method
     * is invoked. If returns false, the client needs to call [SurfaceTexture.release].
     * Most applications should return true.
     *
     * @param surface The surface about to be destroyed
     */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
        pause()
        return true
    }

    /**
     * Invoked when a [TextureView]'s SurfaceTexture is ready for use.
     *
     * @param surface The surface returned by
     * [android.view.TextureView.getSurfaceTexture]
     * @param width The width of the surface
     * @param height The height of the surface
     */
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
//        texture = surface
        checkAlive()
    }

    override fun onPictureTaken(data: ByteArray?) {
        this.data = data
    }

    /**
     * 打开相机。
     */
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!stoped) {
            pause()
        }
        @SuppressLint("WrongConstant") val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()
        try {
            val camIDs = manager.cameraIdList
            val camCnt = camIDs.size
            cameraFacing = if (cameraFacing < camCnt) cameraFacing else camCnt - 1

            handler?.postDelayed({
                try {
                    textureView = previewView.textureView
                    texture = textureView.surfaceTexture
                    if (textureView.isAvailable) {
                        logOutput("start", "ready to start camera2")
                        manager.openCamera(camIDs[cameraFacing], this, handler)
                    }
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }, Cam2.hardwareDelay.toLong())

            timenow = System.currentTimeMillis()
            timestart = timenow
        } catch (e: Exception) {
            e.printStackTrace()
        }
        exe.shutdown()
        exe = Executors.newSingleThreadScheduledExecutor()
        exe.scheduleAtFixedRate(
                this::checkAlive,
                hardwareDelay * 2.toLong(),
                hardwareDelay * 3.toLong(),
                TimeUnit.MILLISECONDS
        )
    }

    /**
     * 关闭相机
     */
    override fun stop() {
        pause()
        exe.shutdown()
        stopBackgroundThread()
    }

    override fun pause() {
        session?.close()
        camera?.close()
        stoped = true
    }

    override fun resume() {
        start()
    }


    override fun setPreferredPreviewSize(width: Int, height: Int) {
        this.width = Math.max(width, height)
        this.height = Math.min(width, height)
    }

    /**
     * 相机对应的预览视图。
     *
     * @return 预览视图
     */
    override fun getDisplayView(): View {
        return this.previewView.textureView
    }

    override fun setPreviewView(previewView: PreviewView) {
        this.previewView = previewView
        this.previewView.textureView.surfaceTextureListener = this
    }

    override fun getPreviewView(): PreviewView {
        return this.previewView
    }

    /**
     * 看到的预览可能不是照片的全部。返回预览视图的全貌。
     *
     * @return 预览视图frame;
     */
    override fun getPreviewFrame(): Rect? {
        return this.previewFrame
    }

    /**
     * 设置权限回调，当手机没有拍照权限时，可在回调中获取。
     *
     * @param callback 权限回调
     */
    override fun setPermissionCallback(callback: PermissionCallback) {
        this.callback = callback
    }

    /**
     * 设置水平方向
     *
     * @param displayOrientation 参数值见 [ORIENTATIONS]
     */
    override fun setDisplayOrientation(displayOrientation: Int) {
        this.displayOrientation = Cam2.ORIENTATIONS.get(displayOrientation)
    }

    /**
     * 获取到拍照权限时，调用些函数以继续。
     */
    override fun refreshPermission() {
        checkAlive()
    }

    /**
     * 获取当前闪光灯状态
     *
     * @return 当前闪光灯状态
     */
    override fun getFlashMode(): Int {
        return ICameraControl.FLASH_MODE_OFF
    }

    override fun setCameraFacing(cameraFacing: Int) {
        this.cameraFacing = cameraFacing
    }

    private fun checkAlive() {
        check = !check
        if (check) {
            timestamp = timenow
            return
        }
        val output: Boolean = timestamp != timenow || timestamp != timestart

        if (!output) {
            Cam2.logOutput("s", "camDie!")
            stoped = true
            handler?.postAtFrontOfQueue {
                Handler().post {
                    pause()
                    start()
                }
            }
        } else {
            Cam2.logOutput("s", "camChkOK!")
            stoped = false
        }
    }

    private inner class CamDebuger : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureStarted(session: CameraCaptureSession?, request: CaptureRequest?, timestamp: Long, frameNumber: Long) {
            if (imageReader.surface == null || previewView.textureView.surfaceTexture == null) {
                handler?.postAtFrontOfQueue {
                    Handler().post {
                        pause()
                        start()
                    }
                }
                return
            }
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }

        override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult
        ) {
            super.onCaptureProgressed(session, request, partialResult)
            Cam2.logOutput("camDebuger", "onCaptureProgressed")
        }

        override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
        ) {
            super.onCaptureFailed(session, request, failure)
            Cam2.logOutput("camDebuger", "onCaptureFailed")
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
            Cam2.logOutput("camDebuger", "onCaptureSequenceCompleted")
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            super.onCaptureSequenceAborted(session, sequenceId)
            Cam2.logOutput("camDebuger", "onCaptureSequenceAborted")
        }

        override fun onCaptureBufferLost(
                session: CameraCaptureSession,
                request: CaptureRequest,
                target: Surface,
                frameNumber: Long
        ) {
            super.onCaptureBufferLost(session, request, target, frameNumber)
            Cam2.logOutput("camDebuger", "onCaptureBufferLost")
        }
    }
}

