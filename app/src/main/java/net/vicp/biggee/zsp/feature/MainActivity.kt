package net.vicp.biggee.zsp.feature


//import androidx.appcompat.app.AppCompatActivity
//import com.baidu.aip.ofr.R
//import kotlinx.android.synthetic.main.activity_main.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.baidu.aip.ImageFrame
import com.baidu.aip.api.FaceApi
import com.baidu.aip.db.DBManager
import com.baidu.aip.entity.Group
import com.baidu.aip.face.FaceDetectManager
import com.baidu.aip.face.FaceFilter
import com.baidu.aip.face.PreviewView
import com.baidu.aip.face.TexturePreviewView
import com.baidu.aip.face.camera.CameraView
import com.baidu.aip.face.camera.ICameraControl
import com.baidu.aip.manager.FaceEnvironment
import com.baidu.aip.manager.FaceLiveness
import com.baidu.aip.manager.FaceSDKManager
import com.baidu.aip.utils.PreferencesUtil
import com.baidu.idl.facesdk.FaceInfo
import com.baidu.idl.facesdk.FaceTracker
import com.tapadoo.alerter.Alerter
import net.vicp.biggee.zsp.R
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), FaceDetectManager.OnFaceDetectListener, FaceFilter.OnTrackListener,
        FaceSDKManager.SdkInitListener {
    // 用于检测人脸。
    private var faceDetectManager: FaceDetectManager? = null
    private val handler: Handler = Handler()
    private val groupId: String = "ZsP"
    private val cameraImageSource: Cam2ImgSrc = Cam2ImgSrc(this)
    @Volatile
    private var identityStatus = FEATURE_DATAS_UNREADY
    private val es = Executors.newSingleThreadScheduledExecutor()
    private var timeStamp: Long = System.currentTimeMillis()
    private lateinit var imageView: ImageView
    private lateinit var sample_text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.zsp_activity_main)
        val previewView = findViewById<FrameLayout>(R.id.zsppreviewView) as TexturePreviewView
        val textureView = previewView.textureView
        sample_text = findViewById<TextView>(R.id.sample_text)
        imageView = findViewById<ImageView>(R.id.zspimageView)
        val width = 1024
        val height = 768

        try {
            PreferencesUtil.initPrefs(this)
            // 使用人脸1：n时使用
            DBManager.getInstance().init(this)
//        livnessTypeTip();
            FaceSDKManager.getInstance().init(this)
            FaceSDKManager.getInstance().setSdkInitListener(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        toast("程序已经打开")

        cameraImageSource.setPreviewView(previewView)
        cameraImageSource.cameraControl.setPreferredPreviewSize(width, height)
        textureView.isOpaque = false
        textureView.keepScreenOn = false
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) {
            previewView.scaleType = PreviewView.ScaleType.FIT_WIDTH
            // 相机坚屏模式
            cameraImageSource.cameraControl.setDisplayOrientation(CameraView.ORIENTATION_PORTRAIT)
        } else {
            previewView.scaleType = PreviewView.ScaleType.FIT_HEIGHT
            // 相机横屏模式
            cameraImageSource.cameraControl.setDisplayOrientation(CameraView.ORIENTATION_HORIZONTAL)
        }

        previewView.rotation = -90f
        textureView.minimumWidth = width
        textureView.minimumHeight = height

        cameraImageSource.setCameraFacing(ICameraControl.CAMERA_FACING_FRONT)
        cameraImageSource.start()
    }

    override fun initStart() {
        toast("sdk init start")
    }

    override fun initSuccess() {
        toast("sdk init success")
        //(cameraImageSource.cameraControl as Cam2).sdkOk = true
        if (FaceApi.getInstance().getGroupList(0, 1000).size <= 0) {
            toast("创建用户组$groupId")
            //创建分组0
            val group = Group()
            group.groupId = groupId
            val ret = FaceApi.getInstance().groupAdd(group)
            toast("添加" + if (ret) "成功" else "失败")
        }

        faceDetectManager = FaceDetectManager(applicationContext)
        FaceSDKManager.getInstance().faceDetector.setMinFaceSize(200)
        faceDetectManager!!.imageSource = cameraImageSource
        faceDetectManager!!.faceFilter.setAngle(20)
        FaceSDKManager.getInstance().faceDetector.setNumberOfThreads(4)
        es.execute {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            // android.os.Process.setThreadPriority (-4);
            FaceApi.getInstance().loadFacesFromDB(groupId)
            toast("人脸数据加载完成，即将开始1：N")
            val count = FaceApi.getInstance().group2Facesets[groupId]?.size
            displaytxt("底库人脸个数：$count")
            identityStatus = IDENTITY_IDLE
        }
        faceDetectManager!!.setOnFaceDetectListener(this@MainActivity)
        faceDetectManager!!.setOnTrackListener(this@MainActivity)

        es.schedule({ faceDetectManager!!.start() }, 1, TimeUnit.SECONDS)
        faceDetectManager!!.setUseDetect(true)
    }

    override fun initFail(errorCode: Int, msg: String?) {
        toast("sdk init fail:$msg")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraImageSource.stop()
    }

    override fun onDetectFace(status: Int, infos: Array<out FaceInfo>, imageFrame: ImageFrame) {
        val bitmap = Bitmap.createBitmap(
                imageFrame.argb, imageFrame.width, imageFrame.height, Bitmap.Config
                .ARGB_8888
        )

        handler.post { imageView.setImageBitmap(bitmap) }

        timeStamp = System.currentTimeMillis()

        if (status == FaceTracker.ErrCode.OK.ordinal) {
            if (identityStatus != IDENTITY_IDLE) {
                return
            }

            es.submit {
                if (infos.isEmpty()) {
                    return@submit
                }

                val starttime = System.currentTimeMillis()
                val rgbScore = FaceLiveness.getInstance().rgbLiveness(
                        imageFrame.argb, imageFrame
                        .width, imageFrame.height, infos[0].landmarks
                )

                val duration = System.currentTimeMillis() - starttime

                runOnUiThread {
                    toast("RGB活体耗时：$duration\tRGB活体得分：$rgbScore")
                }

                if (rgbScore > FaceEnvironment.LIVENESS_RGB_THRESHOLD) {
                    val raw = Math.abs(infos[0].headPose[0])
                    val patch = Math.abs(infos[0].headPose[1])
                    val roll = Math.abs(infos[0].headPose[2])
                    // 人脸的三个角度大于20不进行识别
                    if (raw > 20 || patch > 20 || roll > 20) {
                        return@submit
                    }

                    identityStatus = IDENTITYING

                    val starting = System.currentTimeMillis()
                    val argb = imageFrame.argb
                    val rows = imageFrame.height
                    val cols = imageFrame.width
                    val landmarks = infos[0].landmarks
//        IdentifyRet identifyRet = FaceApi.getInstance().identity(argb, rows, cols, landmarks, groupId);
                    val identifyRet = FaceApi.getInstance().identity(argb, rows, cols, landmarks, groupId)

                    toast(identifyRet.userId + "特征抽取对比耗时:" + (System.currentTimeMillis() - starting))
                    identityStatus = IDENTITY_IDLE
                }  //toast("rgb活体分数过低");
            }
//              showFrame(frame, infos)
        }
    }

    /**
     * 追踪到某张人脸
     *
     * @param trackedModel 人脸信息
     */
    override fun onTrack(trackedModel: FaceFilter.TrackedModel?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }


    private fun toast(s: String) {
        Alerter.create(this)
                .setTitle(title.toString())
                .setText(s)
                .hideIcon()
                .show()
    }

    private fun displaytxt(s: String) {
        handler.post {
            sample_text.text = s
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetectManager?.imageSource?.stop()
        faceDetectManager?.stop()
    }

    override fun onStop() {
        super.onStop()
        faceDetectManager?.stop()
    }

    companion object {
        private const val FEATURE_DATAS_UNREADY = 1
        private const val IDENTITY_IDLE = 2
        private const val IDENTITYING = 3
    }
}
