package net.vicp.biggee.zsp.feature

import android.content.Context
import com.baidu.aip.ImageFrame
import com.baidu.aip.face.ArgbPool
import com.baidu.aip.face.ImageSource
import com.baidu.aip.face.PreviewView
import com.baidu.aip.face.camera.ICameraControl
import com.baidu.aip.manager.FaceDetector

class Cam2ImgSrc(context: Context) : ImageSource(), ICameraControl.OnFrameListener<ByteArray> {
    /**
     * 相机控制类
     */
    val cameraControl: ICameraControl<ByteArray>
    private val argbPool = ArgbPool()
    private var cameraFaceType = ICameraControl.CAMERA_FACING_FRONT

    init {
        cameraControl = Cam2(context)
        cameraControl.setCameraFacing(cameraFaceType)
        cameraControl.setOnFrameListener(this)
    }

    companion object {
        private const val logtag = "Z's P-C2IS-"
    }

    override fun onPreviewFrame(data: ByteArray, rotation: Int, width: Int, height: Int) {
        //Cam2.logOutput(logtag+"oPF",data.byteCount.toString())
        var w = width
        var h = height

        var argb: IntArray? = argbPool.acquire(w, h)

        if (argb == null || argb.size != w * h) {
            argb = IntArray(w * h)
        }

        val r = if (rotation < 0) 360 + rotation else rotation
//            val starttime = System.currentTimeMillis()

        FaceDetector.yuvToARGB(data, width, height, argb, rotation, 0)

        // 旋转了90或270度。高宽需要替换
        if (r % 180 == 90) {
            val temp = w
            w = h
            h = temp
        }

        val frame = ImageFrame()
        frame.argb = argb
        frame.width = w
        frame.height = h
        frame.pool = argbPool
        val listeners = listeners
        for (listener in listeners) {
            listener.onFrameAvailable(frame)
        }
    }

    fun setCameraFacing(type: Int) {
        this.cameraFaceType = type
        cameraControl.setCameraFacing(type)
    }

    override fun start() {
        cameraControl.start()
    }

    override fun stop() {
        cameraControl.stop()
    }

    override fun setPreviewView(previewView: PreviewView) {
        cameraControl.previewView = previewView
    }
}