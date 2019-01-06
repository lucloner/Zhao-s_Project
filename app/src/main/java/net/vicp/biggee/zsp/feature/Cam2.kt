package net.vicp.biggee.zsp.feature


//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import cn.mclover.util.ImageConvert
import com.baidu.aip.face.PreviewView
import com.baidu.aip.face.camera.ICameraControl
import com.baidu.aip.face.camera.PermissionCallback
import java.util.concurrent.*

@TargetApi(Build.VERSION_CODES.N)
class Cam2 internal constructor(private var context: Context) : ICameraControl<Bitmap>, CameraDevice.StateCallback(),
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
    @Volatile
    private lateinit var mRGBframeBitmap: Bitmap
    private lateinit var listener: ICameraControl.OnFrameListener<Any>
    private var exe = Executors.newSingleThreadScheduledExecutor()
    @Volatile
    private var timestamp = timenow
    private var check = false
    private val imageReader: ImageReader by lazy { ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2) }
    private lateinit var callback: PermissionCallback
    var sdkOk: Boolean = false
    private var backgroundThread: HandlerThread? = null
    private var handler: Handler? = null
    val frameQueue = LinkedBlockingDeque<Runnable>()
    val framePool = ThreadPoolExecutor(2, MainActivity.CPUCORES, 1000 / FPS * MainActivity.CPUCORES.toLong(), TimeUnit.MILLISECONDS, frameQueue, this)

    companion object {
        const val FPS = 30
        private const val hardwareDelay = 1000
        private const val CAMDIE = 32768
        private const val logtag = "Z's P-C2-"
        private const val REQUEST_CAMERA_PERMISSION = 1
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

    init {
        ImageConvert()
    }

    /**
     * 选择变换
     *
     * @param origin 原图
     * @param alpha  旋转角度，可正可负
     * @return 旋转后的图片
     */
    private fun rotateBitmap(origin: Bitmap, alpha: Float): Bitmap {
        val width = origin.width
        val height = origin.height
        val matrix = Matrix()
        matrix.setRotate(alpha)
        // 围绕原地进行旋转
        val newBM = Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false)
        if (newBM == origin) {
            return newBM
        }
        origin.recycle()
        return newBM
    }

    /**
     * Constructs a new `Thread`.  Implementations may also initialize
     * priority, name, daemon status, `ThreadGroup`, etc.
     *
     * @param r a runnable to be executed by new thread instance
     * @return constructed thread, or `null` if the request to
     * create a thread is rejected
     */
    override fun newThread(r: Runnable?): Thread? {
        if (System.currentTimeMillis() - timestart > 1000 / FPS * framePool.corePoolSize || frameQueue.size > framePool.maximumPoolSize) {
            frameQueue.clear()
        }
        return Thread {
            try {
                r?.run()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        handler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            handler?.removeCallbacks(null)
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
        try {
            val image = reader?.acquireLatestImage() ?: return
            val plane = image.planes
            val mYUVBytes = arrayOfNulls<ByteArray>(plane.size)

            for (i in mYUVBytes.indices) mYUVBytes[i] = ByteArray(plane[i].buffer.capacity())

            val mRGBBytes = IntArray(width * height)

            for (i in plane.indices) plane[i].buffer.get(mYUVBytes[i])
            val yRowStride = plane[0].rowStride
            val uvRowStride = plane[1].rowStride
            val uvPixelStride = plane[1].pixelStride

            ImageConvert.convertYUV420ToARGB8888(
                    mYUVBytes[0],
                    mYUVBytes[1],
                    mYUVBytes[2],
                    mRGBBytes,
                    image.width,
                    image.height,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false)

            val mRGBframeBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            mRGBframeBitmap.setPixels(mRGBBytes, 0, image.width, 0, 0, image.width, image.height)

            this.mRGBframeBitmap = rotateBitmap(mRGBframeBitmap, 270f)

            image.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (sdkOk && !stoped) {
            val bmp = this.mRGBframeBitmap
            framePool.execute {
                Thread.currentThread().priority = Thread.MAX_PRIORITY
                listener.onPreviewFrame(bmp, 0, bmp.width, bmp.height)
                bmp.recycle()
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
            var width = this.width
            var height = this.height
            if (displayOrientation % 180 == 90) {
                width = this.height
                height = this.width
            }

            previewFrame = Rect(0, 0, width, height)
            textureView = previewView.textureView
            texture = textureView.surfaceTexture

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
//            mPreviewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,90)

            mPreviewRequestBuilder.addTarget(surface)
            mPreviewRequestBuilder.addTarget(imageReader.surface)

            val captureRequest = mPreviewRequestBuilder.build()

            logOutput(logtag, "width:" + width +
                    " height:" + height +
                    " displayOrientation:" + displayOrientation +
                    " previewView.textureView.width:" + previewView.textureView.width +
                    " previewView.textureView.height:" + previewView.textureView.height
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
        texture = surface
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
        texture = surface
        checkAlive()
    }

    override fun onPictureTaken(data: ByteArray?) {
        this.data = data
    }

    /**
     * 打开相机。
     */
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
                    var thisActivity: AppCompatActivity? = null
                    if (context is AppCompatActivity) {
                        thisActivity = context as AppCompatActivity
                    }

                    // Here, thisActivity is the current activity
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        // Should we show an explanation?
                        if (ActivityCompat.shouldShowRequestPermissionRationale(
                                        thisActivity!!,
                                        Manifest.permission.CAMERA
                                )
                        ) {
                            // Show an expanation to the user *asynchronously* -- don't block
                            // this thread waiting for the user's response! After the user
                            // sees the explanation, try again to request the permission.
                        } else {
                            // No explanation needed, we can request the permission.
                            ActivityCompat.requestPermissions(
                                    thisActivity,
                                    arrayOf(Manifest.permission.CAMERA),
                                    REQUEST_CAMERA_PERMISSION
                            )
                            // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                            // app-defined int constant. The callback method gets the
                            // result of the request.
                            return@postDelayed
                        }
                    }
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
        exe = Executors.newSingleThreadScheduledExecutor(this)
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

