package net.vicp.biggee.zsp.feature


//import androidx.appcompat.app.AppCompatActivity
//import com.baidu.aip.ofr.R
//import kotlinx.android.synthetic.main.activity_main.*
import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.view.ViewGroup
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
import java.util.concurrent.*
import kotlin.collections.HashSet
import kotlin.math.min

class MainActivity : AppCompatActivity(), FaceDetectManager.OnFaceDetectListener, FaceFilter.OnTrackListener,
        FaceSDKManager.SdkInitListener, DialogInterface.OnClickListener, DialogInterface.OnDismissListener, ThreadFactory, TextToSpeech.OnInitListener {

    // 用于检测人脸。
    private val faceDetectManager: FaceDetectManager by lazy { FaceDetectManager(applicationContext) }
    private val handler: Handler = Handler()
    private val groupId: String = "ZsP"
    private val cameraImageSource: Cam2ImgSrc by lazy { Cam2ImgSrc(applicationContext) }
    private val cameraControl: Cam2 by lazy { cameraImageSource.cameraControl as Cam2 }
    private val previewView by lazy { findViewById<FrameLayout>(R.id.zsppreviewView) as TexturePreviewView }
    @Volatile
    private var identityStatus = FEATURE_DATAS_UNREADY
    private val es: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor() }
    @Volatile
    private var timeStamp: Long = System.currentTimeMillis()
    @Volatile
    private var timeidle = timeStamp
    private lateinit var imageView: ImageView
    private lateinit var sampletext: TextView
    private var userIdOfMaxScore = ""
    private var maxScore = 0f
    private val tts: TextToSpeech by lazy {
        TextToSpeech(applicationContext, this)
    }
    @Volatile
    private var unknownFace: Bitmap? = null
    private var faceTOreg: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var wait = false
    private var txtName: EditText? = null
    private var showFace = false
    private val originLayout: ViewGroup.LayoutParams by lazy { sampletext.layoutParams }
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }
    private val rate by lazy { (0.75 * screenHeight / screenWidth).toFloat() }
    private val rateX by lazy { (screenWidth / WIDTH).toFloat() }
    private val rateY by lazy { (screenHeight / HEIGHT).toFloat() }
    private val recogQueue: LinkedBlockingQueue<Runnable> by lazy { LinkedBlockingQueue<Runnable>(CPUCORES) }
    private val recogPool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
                1,
                CPUCORES,
                1,
                TimeUnit.MILLISECONDS,
                recogQueue,
                this,
                ThreadPoolExecutor.CallerRunsPolicy()
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }
    private val recogGroup: ThreadGroup by lazy { ThreadGroup("${System.currentTimeMillis()}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.zsp_activity_main)

        val textureView = previewView.textureView
        sampletext = findViewById(R.id.sample_text)
        imageView = findViewById(R.id.zspimageView)

        // Here, thisActivity is the current activity
        if (this.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (this.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            ) {
                // Show an expanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                this.requestPermissions(
                        arrayOf(Manifest.permission.CAMERA),
                        Cam2.REQUEST_CAMERA_PERMISSION
                )
                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        }

        try {
            PreferencesUtil.initPrefs(applicationContext)
            // 使用人脸1：n时使用
            DBManager.getInstance().init(applicationContext)
//        livnessTypeTip();
            FaceSDKManager.getInstance().init(this)
            FaceSDKManager.getInstance().setSdkInitListener(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }


        toast("程序已经打开")
        orientation = resources.configuration.orientation
        val isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT

        var width = WIDTH
        var height = HEIGHT

        if (!isPortrait) {
            height = WIDTH
            width = HEIGHT
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

        textureView.scaleX = rate

        cameraImageSource.setCameraFacing(ICameraControl.CAMERA_FACING_FRONT)

        //cameraImageSource.start()

        Cam2.logOutput(logtag, "$originLayout")

        tts.isSpeaking
    }

    /**
     * Called to signal the completion of the TextToSpeech engine initialization.
     *
     * @param status [TextToSpeech.SUCCESS] or [TextToSpeech.ERROR].
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.speak("语音系统已连接", TextToSpeech.QUEUE_ADD, null, null)
        } else {
            Toast.makeText(this, status.toString(), Toast.LENGTH_LONG).show()
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
    override fun newThread(r: Runnable?): Thread? {
        if (recogPool.poolSize >= recogPool.maximumPoolSize && recogQueue.remainingCapacity() == 0) {
            recogQueue.clear()
        }
        return Thread(
                recogGroup,
                r,
                "recog${System.currentTimeMillis()}",
                0
        ).apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * This method will be invoked when the dialog is dismissed.
     *
     * @param dialog the dialog that was dismissed will be passed into the
     * method
     */
    override fun onDismiss(dialog: DialogInterface?) {
        Cam2.logOutput("$logtag oD", "Dismissed!:$wait\t${cameraControl.sdkOk}")
        cameraControl.sdkOk = wait
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        Cam2.logOutput("$logtag oC", "dispatchTouchEvent!${ev?.action}")
        if (!cameraControl.sdkOk) {
            return true
        }
        if (ev?.action == MotionEvent.ACTION_UP) {
            wait = cameraControl.sdkOk
            cameraControl.sdkOk = false
            var hasFace = false
            try {
                faceTOreg = unknownFace!!.copy(Bitmap.Config.ARGB_8888, false)
                hasFace = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            AlertDialog.Builder(this@MainActivity).apply {
                setTitle("操作?")
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
                    setMessage("请输入名称：")
                } else {
                    setMessage("要删除人脸吗：")
                }
                setNeutralButton("删除已注册的人脸", this@MainActivity)
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
        displaytxt("")
        when (which) {
            DialogInterface.BUTTON_POSITIVE -> {  //注册
                handler.post {
                    imageView.setImageBitmap(faceTOreg)
                }
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
                            flushAPI()
                        }
                    }
                } else if (ret == -1) {
                    toast("抽取特征失败")
                } else {
                    toast("未检测到人脸")
                }
            }
            DialogInterface.BUTTON_NEUTRAL -> {   //删除
                dialog?.dismiss()
                val choices = HashSet<String>()
                val users = DBManager.getInstance().queryUserByGroupId(groupId)
                val usernames = Array<String>(users.size) { users[it].userInfo }
                handler.postDelayed({
                    AlertDialog.Builder(this@MainActivity).apply {
                        setTitle("请选择要删除的用户名称")
                        setMultiChoiceItems(usernames, BooleanArray(users.size) { false }) { _: DialogInterface, which: Int, isChecked: Boolean ->
                            val uid = users[which].userId
                            val featureList = DBManager.getInstance().queryFeature(groupId, uid)
                            try {
                                val bmp = getRegedFace(featureList[0]!!.imageName)
                                if (bmp != null) {
                                    handler.post { imageView.setImageBitmap(bmp) }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            if (isChecked) {
                                choices.add(uid)
                            } else {
                                choices.remove(uid)
                            }
                        }
                        setPositiveButton("删除选中") { _: DialogInterface, _: Int ->
                            choices.forEach {
                                DBManager.getInstance().deleteUser(it, groupId)
                            }
                            flushAPI()
                        }
                        setNegativeButton("取消", this@MainActivity)
                        setOnDismissListener(this@MainActivity)
                        wait = cameraControl.sdkOk
                        cameraControl.sdkOk = false
                        show()
                    }
                }, 10)
            }
        }
    }

    private fun flushAPI() {
        identityStatus = FEATURE_DATAS_UNREADY

        unknownFace = null
        maxScore = -1f
        userIdOfMaxScore = ""

        if (FaceApi.getInstance().getGroupList(0, 1000).size <= 0) {
            toast("创建用户组$groupId")
            //创建分组0
            val group = Group()
            group.groupId = groupId
            val ret = FaceApi.getInstance().groupAdd(group)
            Thread.sleep(2000)
            toast("添加${if (ret) "成功" else "失败"}")
        }

        FaceApi.getInstance().group2Facesets.clear()
        FaceApi.getInstance().loadFacesFromDB(groupId)
        toast("人脸数据加载完成，即将开始1：N")
        val count = FaceApi.getInstance().group2Facesets[groupId]!!.size
        displaytxt("底库人脸个数：$count")

        handler.postDelayed({ identityStatus = IDENTITY_IDLE }, 100)
    }

    override fun initStart() {
        toast("sdk init start")
    }

    override fun initSuccess() {
        toast("sdk init success")

        FaceSDKManager.getInstance().faceDetector.setMinFaceSize(200)
        faceDetectManager.imageSource = cameraImageSource
        faceDetectManager.faceFilter.setAngle(20)
        FaceSDKManager.getInstance().faceDetector.setNumberOfThreads(CPUCORES / 2)
        es.schedule({
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            flushAPI()
            cameraControl.sdkOk = true
            faceDetectManager.start()
            es.scheduleAtFixedRate({
                if (System.currentTimeMillis() - timeidle < IDLE_DELAY || identityStatus == IDENTITYING) {
                    return@scheduleAtFixedRate
                }
                System.gc()
            }, IDLE_DELAY.toLong(), IDLE_DELAY.toLong(), TimeUnit.MILLISECONDS)
            Cam2.logOutput("$logtag iS", "starting done")
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

        val timeStamp = System.currentTimeMillis()
        this.timeStamp = timeStamp

        if (imageFrame == null) {
            Cam2.logOutput("$logtag oD", "null:${imageFrame.toString()}")
            return
        }

        handler.post {
            showFrame(imageFrame, infos)

            if (timeStamp - timeidle > 5000) {
                unknownFace = null
            }
            if (identityStatus == IDENTITY_IDLE && !showFace) {
                imageView.setImageBitmap(
                        unknownFace ?: Bitmap.createBitmap(
                                imageFrame.argb,
                                imageFrame.width,
                                imageFrame.height,
                                Bitmap.Config.ARGB_8888
                        ))
            }
        }

        if (identityStatus != IDENTITY_IDLE) {
            return
        }

        if (status != FaceTracker.ErrCode.OK.ordinal || infos == null) {
            return
        }

        recogPool.execute {
            identityStatus = IDENTITYING
            try {
                val txt = StringBuilder()

                if (infos.isEmpty()) {
                    throw Exception(txt.toString())
                }

                Cam2.logOutput("$logtag oD", "Detected!")

                Thread.currentThread().priority = Thread.MAX_PRIORITY - 3
                val rgbScore = FaceLiveness.getInstance().rgbLiveness(
                        imageFrame.argb, imageFrame
                        .width, imageFrame.height, infos[0].landmarks
                )
                txt.append("RGB活体耗时：${System.currentTimeMillis() - timeStamp}ms \t")
                txt.append("RGB活体得分：$rgbScore \n")

                if (rgbScore <= FaceEnvironment.LIVENESS_RGB_THRESHOLD) {
                    txt.append("活体检测分数过低")
                    toast(txt.toString())
                    throw Exception(txt.toString())
                }

                val raw = Math.abs(infos[0].headPose[0])
                val patch = Math.abs(infos[0].headPose[1])
                val roll = Math.abs(infos[0].headPose[2])
                // 人脸的三个角度大于20不进行识别
                if (raw > 20 || patch > 20 || roll > 20) {
                    txt.append("角度大于20度,请正视屏幕: ($raw,$patch,$roll) ")
                    toast(txt.toString())
                    throw Exception(txt.toString())
                }

                val argb = imageFrame.argb
                val rows = imageFrame.height
                val cols = imageFrame.width
                val landmarks = infos[0].landmarks

                Thread.currentThread().priority = Thread.MAX_PRIORITY - 2

                val identifyRet = FaceApi.getInstance().identity(argb, rows, cols, landmarks, groupId)
                val score = identifyRet.score
                val userId = identifyRet.userId

                if (score < 80) {
                    txt.append("比对得分太低: $score \t")
                    txt.append("最近似的结果为: ${identifyRet.userId} ")
                    displaytxt(txt.toString())
                    throw Exception(txt.toString())
                }

                recogPool.purge()

                unknownFace = null

                if (userIdOfMaxScore == userId) {
                    if (score < maxScore) {
                        txt.append(sampletext.text)
                        txt.append("↓")
                    } else {
                        maxScore = score
                        txt.append("userId: $userId \tscore: $score↑")
                    }
                    displaytxt(txt.toString())
                    throw Exception(txt.toString())
                } else {
                    userIdOfMaxScore = userId
                    maxScore = score
                }

                txt.append("userId:$userId \tscore: $score \t")

                val user = FaceApi.getInstance().getUserInfo(groupId, userId)
                        ?: throw Exception(txt.toString())

                txt.append("名字: ${user.userInfo} \n")
                try {
                    showFace(getRegedFace(user.featureList[0]!!.imageName)!!, user.userInfo)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val timeidle = System.currentTimeMillis()
                txt.append("特征抽取对比耗时: ${timeidle - timeStamp} \t")

                try {
                    val t = min(min(this.timeidle, cameraControl.timestart), Thread.currentThread().name.substring(5).toLong())
                    txt.append("最长可能时间: ${timeidle - t}")
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                displaytxt(txt.toString())

                this.timeidle = timeidle

                cameraControl.framePool.purge()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            identityStatus = IDENTITY_IDLE
        }
    }

    private fun getRegedFace(imageName: String): Bitmap? {
        val f = File(FileUitls.getFaceDirectory(), imageName)
        if (f.exists()) {
            return BitmapFactory.decodeFile(f.absolutePath)
        }
        return null
    }

    /**
     * 追踪到某张人脸
     *
     * @param trackedModel 人脸信息
     */
    override fun onTrack(trackedModel: FaceFilter.TrackedModel?) {
        timeidle = System.currentTimeMillis()
        unknownFace = trackedModel?.cropFace()
    }

    private fun showFace(face: Bitmap, name: String) {
        if (Alerter.isShowing) {
            Alerter.hide()
        }
        Alerter.create(this)
                .setTitle("识别！")
                .setText(name)
                .hideIcon()
                .setBackgroundColorInt(R.color.colorAccent)
                .show()
        showFace = true
        handler.post {
            imageView.setImageBitmap(face)
            if (tts.isSpeaking) {
                tts.stop()
            }
            tts.speak("识别,$name", TextToSpeech.QUEUE_FLUSH, null, null)
        }
        handler.postDelayed({ showFace = false }, 3000)
    }

    private fun toast(s: String) {
        if (Alerter.isShowing) {
            Alerter.hide()
        }
        Alerter.create(this)
                .setTitle(title.toString())
                .setText(s)
                .hideIcon()
                .enableVibration(false)
                .setBackgroundColorInt(R.color.colorPrimary)
                .show()

        handler.post({
            if (!tts.isSpeaking) {
                tts.speak("信息,$s", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (cameraControl.sdkOk) {
            faceDetectManager.start()
        }
    }

    private fun displaytxt(s: String) {
        handler.post {
            sampletext.text = s
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetectManager.imageSource?.stop()
        faceDetectManager.stop()
        recogPool.shutdown()
        es.shutdown()
    }

    override fun onStop() {
        super.onStop()
        faceDetectManager.stop()
    }


    /**
     * 绘制人脸框。
     */
    fun showFrame(imageFrame: ImageFrame?, faceInfos: Array<out FaceInfo>?) {
        if (faceInfos == null || faceInfos.size == 0 || imageFrame == null) {
            sampletext.layoutParams = originLayout
            sampletext.background = null
            return
        }
        val faceInfo = faceInfos[0]
        val rectF = RectF()
        rectF.set(getFaceRect(faceInfo, imageFrame))
        // 检测图片的坐标和显示的坐标不一样，需要转换。
        previewView.mapFromOriginalRect(rectF)
//        Cam2.logOutput(logtag, rectF.toShortString())

        val layoutParams = FrameLayout.LayoutParams(
                rectF.width().toInt(),
                rectF.height().toInt()
        )
        layoutParams.leftMargin = rectF.left.toInt()
        layoutParams.topMargin = rectF.top.toInt()
        sampletext.layoutParams = layoutParams
        sampletext.background = getDrawable(R.drawable.boarder)
    }

    /**
     * 获取人脸框区域。
     *
     * @return 人脸框区域
     */
    // TODO padding?
    fun getFaceRect(faceInfo: FaceInfo, frame: ImageFrame): Rect {
        val rect = Rect()
        val points = IntArray(8)
        faceInfo.getRectPoints(points)

        var left = points[2].toFloat()
        var top = points[3].toFloat()
        val right = points[6]
        val bottom = points[7]
        //随便调节的
        //TODO:请提供公式
        val width = (right - left) * rateX * rate
        val height = (bottom - top) * rateY * rate

        val x = faceInfo.mCenter_x * rateX * rate
        val y = faceInfo.mCenter_y * rateY * rate

        left = (screenWidth - x) - width / 2
        top = y - height / 2

//        Cam2.logOutput(logtag + "r", "$left $top $right $bottom ${faceInfo.mCenter_x} ${faceInfo.mCenter_y}")

        rect.top = top.toInt()
        rect.left = left.toInt()
        rect.right = (left + width).toInt()
        rect.bottom = (top + height).toInt()

        return rect
    }

    companion object {
        private const val FEATURE_DATAS_UNREADY = 1
        private const val IDENTITY_IDLE = 2
        private const val IDENTITYING = 3
        private const val logtag = "MA-"
        const val CPUCORES = 8
        private const val IDLE_DELAY = 60000
        var orientation: Int = 0
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
    }
}
