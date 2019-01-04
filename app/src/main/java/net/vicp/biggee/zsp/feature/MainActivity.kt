package net.vicp.biggee.zsp.feature


//import androidx.appcompat.app.AppCompatActivity
//import com.baidu.aip.ofr.R
//import kotlinx.android.synthetic.main.activity_main.*
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.widget.*
import com.baidu.aip.ImageFrame
import com.baidu.aip.api.FaceApi
import com.baidu.aip.db.DBManager
import com.baidu.aip.entity.Feature
import com.baidu.aip.entity.Group
import com.baidu.aip.entity.User
import com.baidu.aip.face.FaceDetectManager
import com.baidu.aip.face.FaceFilter
import com.baidu.aip.face.PreviewView
import com.baidu.aip.face.TexturePreviewView
import com.baidu.aip.face.camera.CameraView
import com.baidu.aip.face.camera.ICameraControl
import com.baidu.aip.manager.FaceDetector
import com.baidu.aip.manager.FaceEnvironment
import com.baidu.aip.manager.FaceLiveness
import com.baidu.aip.manager.FaceSDKManager
import com.baidu.aip.utils.FeatureUtils
import com.baidu.aip.utils.FileUitls
import com.baidu.aip.utils.PreferencesUtil
import com.baidu.idl.facesdk.FaceInfo
import com.baidu.idl.facesdk.FaceTracker
import com.tapadoo.alerter.Alerter
import net.vicp.biggee.zsp.R
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), FaceDetectManager.OnFaceDetectListener, FaceFilter.OnTrackListener,
        FaceSDKManager.SdkInitListener, DialogInterface.OnClickListener, DialogInterface.OnDismissListener {
    // 用于检测人脸。
    private val faceDetectManager: FaceDetectManager by lazy { FaceDetectManager(applicationContext) }
    private val handler: Handler = Handler()
    private val groupId: String = "ZsP"
    private val cameraImageSource: Cam2ImgSrc by lazy { Cam2ImgSrc(this) }
    private val previewView by lazy { findViewById<FrameLayout>(R.id.zsppreviewView) as TexturePreviewView }
    @Volatile
    private var identityStatus = FEATURE_DATAS_UNREADY
    private val es: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor() }
    private var timeStamp: Long = System.currentTimeMillis()
    private lateinit var imageView: ImageView
    private lateinit var sample_text: TextView
    private var userIdOfMaxScore = ""
    private var maxScore = 0f
    private var unknownFace: Bitmap? = null
    private var faceTOreg: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var wait = false
    private var txtName: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.zsp_activity_main)

        val textureView = previewView.textureView
        sample_text = findViewById<TextView>(R.id.sample_text)
        imageView = findViewById<ImageView>(R.id.zspimageView)


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
        orientation = resources.configuration.orientation
        val isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT

        var width = 1080
        var height = 1920

        if (!isPortrait) {
            height = 1080
            width = 1920
        }

        cameraImageSource.setPreviewView(previewView)
        cameraImageSource.cameraControl.setPreferredPreviewSize(width, height)
        textureView.isOpaque = false
        textureView.keepScreenOn = false

        if (isPortrait) {
            previewView.scaleType = PreviewView.ScaleType.FIT_WIDTH
            // 相机坚屏模式
            cameraImageSource.cameraControl.setDisplayOrientation(CameraView.ORIENTATION_PORTRAIT)
        } else {
            previewView.scaleType = PreviewView.ScaleType.FIT_HEIGHT
            // 相机横屏模式
            cameraImageSource.cameraControl.setDisplayOrientation(CameraView.ORIENTATION_HORIZONTAL)
        }

        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val rate: Float = screenWidth / screenHeight.toFloat()

        textureView.scaleY = rate * 4 / 3f

        cameraImageSource.setCameraFacing(ICameraControl.CAMERA_FACING_FRONT)
        //cameraImageSource.start()
    }

    /**
     * This method will be invoked when the dialog is dismissed.
     *
     * @param dialog the dialog that was dismissed will be passed into the
     * method
     */
    override fun onDismiss(dialog: DialogInterface?) {
        if (!wait) {
            (cameraImageSource.cameraControl as Cam2).sdkOk = true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        Cam2.logOutput("$logtag oC", "dispatchTouchEvent!${ev?.action}")
        if (ev?.action == MotionEvent.ACTION_UP) {
            (cameraImageSource.cameraControl as Cam2).sdkOk = false
            var hasFace = false
            try {
                faceTOreg = unknownFace!!.copy(Bitmap.Config.ARGB_8888, false)
                hasFace = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            AlertDialog.Builder(this@MainActivity).apply {
                setTitle("操作?")
                setMessage("请选择")
                if (hasFace) {
                    val faceView = ImageView(this@MainActivity).apply {
                        setImageBitmap(faceTOreg)
                    }
                    val layout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        txtName = EditText(this@MainActivity)
                        addView(txtName)
                        addView(faceView)
                    }
                    setView(layout)
                    setPositiveButton("注册", this@MainActivity)
                }
                setNeutralButton("删除", this@MainActivity)
                setNegativeButton("关闭", this@MainActivity)
                setOnDismissListener(this@MainActivity)
                show()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * This method will be invoked when a button in the dialog is clicked.
     *
     * @param dialog the dialog that received the click
     * @param which the button that was clicked (ex.
     * [DialogInterface.BUTTON_POSITIVE]) or the position
     * of the item clicked
     */
    override fun onClick(dialog: DialogInterface?, which: Int) {
        Cam2.logOutput("$logtag oC", "Dialog Clicked!")
        wait = true
        (cameraImageSource.cameraControl as Cam2).sdkOk = false
        when (which) {
            DialogInterface.BUTTON_POSITIVE -> {  //注册
                val argbImg = FeatureUtils.getImageInfo(faceTOreg)
                val bytes = ByteArray(2048)
                val ret = FaceSDKManager.getInstance().faceFeature.faceFeature(argbImg, bytes, 50)
                if (ret == FaceDetector.NO_FACE_DETECTED) {
                    toast("未检测到人脸，可能原因：人脸太小（必须大于最小检测人脸minFaceSize），或者人脸角度太大，人脸不是朝上")
                } else if (ret == 512) {
                    val uid = UUID.randomUUID().toString()
                    val faceDir = FileUitls.getFaceDirectory()
                    val saveFacePath = File(faceDir, uid)
                    if (!FileUitls.saveFile(saveFacePath, faceTOreg)) {
                        toast("创建文件$saveFacePath 失败!")
                    } else {
                        //toast("创建文件$saveFacePath 成功!")
                        val feature = Feature().apply {
                            groupId = this@MainActivity.groupId
                            userId = uid
                            feature = bytes
                            imageName = saveFacePath.name
                        }
                        val user = User().apply {
                            userId = uid
                            userInfo = txtName?.text.toString()
                            groupId = this@MainActivity.groupId
                            featureList.add(feature)
                        }
                        if (!FaceApi.getInstance().userAdd(user)) {
                            if (saveFacePath.exists()) {
                                saveFacePath.delete()
                            }
                            toast("注册失败")
                        } else {
                            showFace(faceTOreg, "注册成功将重启")
                            Thread.sleep(3000)
                            val i = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
                            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(i)
                        }
                    }
                } else if (ret == -1) {
                    toast("抽取特征失败")
                } else {
                    toast("未检测到人脸")
                }
            }
            DialogInterface.BUTTON_NEUTRAL -> {   //删除

            }
        }
        (cameraImageSource.cameraControl as Cam2).sdkOk = true
        wait = false
    }

    override fun initStart() {
        toast("sdk init start")
    }

    override fun initSuccess() {
        toast("sdk init success")

        if (FaceApi.getInstance().getGroupList(0, 1000).size <= 0) {
            toast("创建用户组$groupId")
            //创建分组0
            val group = Group()
            group.groupId = groupId
            val ret = FaceApi.getInstance().groupAdd(group)
            toast("添加" + if (ret) "成功" else "失败")
        }

        FaceSDKManager.getInstance().faceDetector.setMinFaceSize(200)
        faceDetectManager.imageSource = cameraImageSource
        faceDetectManager.faceFilter.setAngle(20)
        FaceSDKManager.getInstance().faceDetector.setNumberOfThreads(4)
        es.schedule({
            if (identityStatus != FEATURE_DATAS_UNREADY) {
                return@schedule
            }
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            // android.os.Process.setThreadPriority (-4);
            FaceApi.getInstance().loadFacesFromDB(groupId)
            toast("人脸数据加载完成，即将开始1：N")
            val count = FaceApi.getInstance().group2Facesets[groupId]!!.size
            displaytxt("底库人脸个数：$count")
            identityStatus = IDENTITY_IDLE
            (cameraImageSource.cameraControl as Cam2).sdkOk = true
            faceDetectManager.start()
            Cam2.logOutput("iS", "starting done")
        }, 1, TimeUnit.SECONDS)
        faceDetectManager.setOnFaceDetectListener(this@MainActivity)
        faceDetectManager.setOnTrackListener(this@MainActivity)
        faceDetectManager.setUseDetect(true)
    }

    override fun initFail(errorCode: Int, msg: String?) {
        toast("sdk init fail:$msg")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraImageSource.stop()
    }

    override fun onDetectFace(status: Int, infos: Array<out FaceInfo>?, imageFrame: ImageFrame?) {
        if (imageFrame == null) {
            Cam2.logOutput("oD", "null:" + imageFrame.toString())
            return
        }
        val bitmap = Bitmap.createBitmap(
                imageFrame.argb, imageFrame.width, imageFrame.height, Bitmap.Config
                .ARGB_8888
        )

        handler.post {
            imageView.setImageBitmap(bitmap)
//            Cam2.logOutput("oDF", bitmap.byteCount.toString())
        }

        timeStamp = System.currentTimeMillis()

        if (status != FaceTracker.ErrCode.OK.ordinal || infos == null) {
            return
        }
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

            if (rgbScore <= FaceEnvironment.LIVENESS_RGB_THRESHOLD) {
                return@submit
            }

            val raw = Math.abs(infos[0].headPose[0])
            val patch = Math.abs(infos[0].headPose[1])
            val roll = Math.abs(infos[0].headPose[2])
            // 人脸的三个角度大于20不进行识别
            if (raw > 20 || patch > 20 || roll > 20) {
                return@submit
            }

            identityStatus = IDENTITYING

            val argb = imageFrame.argb
            val rows = imageFrame.height
            val cols = imageFrame.width
            val landmarks = infos[0].landmarks

            val identifyRet = FaceApi.getInstance().identity(argb, rows, cols, landmarks, groupId)
            val score = identifyRet.score
            val userId = identifyRet.userId

            if (score < 80) {
                return@submit
            }

            handler.post {
                val txt = StringBuilder()

                if (userIdOfMaxScore == userId) {
                    if (score < maxScore) {
                        txt.append(sample_text.text)
                        txt.append("↓")
                    } else {
                        maxScore = score
                        txt.append("userId：$userId\tscore：$score↑")
                    }
                    displaytxt(txt.toString())
                    return@post
                } else {
                    userIdOfMaxScore = userId
                    maxScore = score
                }

                txt.append("userId：$userId\tscore：$score")

                val user = FaceApi.getInstance().getUserInfo(groupId, userId) ?: return@post

                txt.append("\n" + user.userInfo)
                val featureList = user.featureList
                if (featureList != null && featureList.size > 0) {
                    val faceDir = FileUitls.getFaceDirectory()
                    if (faceDir != null && faceDir.exists()) {
                        val file = File(faceDir, featureList[0].imageName)
                        if (file.exists()) {
                            val face = BitmapFactory.decodeFile(file.absolutePath)
                            showFace(face, userId)
                        }
                    }
                }
                txt.append("\n特征抽取对比耗时:" + (System.currentTimeMillis() - timeStamp))
                displaytxt(txt.toString())
            }
            identityStatus = IDENTITY_IDLE
        }
    }

    /**
     * 追踪到某张人脸
     *
     * @param trackedModel 人脸信息
     */
    override fun onTrack(trackedModel: FaceFilter.TrackedModel?) {
        unknownFace = trackedModel?.cropFace()
    }

    private fun showFace(face: Bitmap, name: String) {
        Alerter.create(this)
                .setTitle("识别！")
                .setText(name)
                .setIcon(face)
                .setBackgroundColorInt(Color.LTGRAY)
                .show()
    }
    
    private fun toast(s: String) {
        Alerter.create(this)
                .setTitle(title.toString())
                .setText(s)
                .hideIcon()
                .enableVibration(false)
                .show()
    }

    override fun onResume() {
        super.onResume()
        if ((cameraImageSource.cameraControl as Cam2).sdkOk) {
            faceDetectManager.start()
        }
    }

    private fun displaytxt(s: String) {
        handler.post {
            sample_text.text = s
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetectManager.imageSource?.stop()
        faceDetectManager.stop()
    }

    override fun onStop() {
        super.onStop()
        faceDetectManager.stop()
    }

    companion object {
        private const val FEATURE_DATAS_UNREADY = 1
        private const val IDENTITY_IDLE = 2
        private const val IDENTITYING = 3
        private const val logtag = "Z's P-MA-"
        var orientation: Int = 0
    }
}
