package net.vicp.biggee.zsp.feature


import android.content.Context
import android.graphics.Bitmap
import com.baidu.aip.ImageFrame
import com.baidu.aip.face.ArgbPool
import com.baidu.aip.face.ImageSource
import com.baidu.aip.face.PreviewView
import com.baidu.aip.face.camera.ICameraControl

class Cam2ImgSrc(context: Context) : ImageSource(), ICameraControl.OnFrameListener<Bitmap> {
    /**
     * 相机控制类
     */
    val cameraControl: ICameraControl<Bitmap>
    private val argbPool = ArgbPool()
    private var cameraFaceType = ICameraControl.CAMERA_FACING_FRONT

    init {
        cameraControl = Cam2(context)
        cameraControl.setCameraFacing(cameraFaceType)
        cameraControl.setOnFrameListener(this)
    }

    override fun onPreviewFrame(data: Bitmap, rotation: Int, width: Int, height: Int) {
        var w = width
        var h = height

        var argb: IntArray? = argbPool.acquire(w, h)

        if (argb == null || argb.size != w * h) {
            argb = IntArray(w * h)
        }

        val r = if (rotation < 0) 360 + rotation else rotation
//            val starttime = System.currentTimeMillis()

        data.getPixels(argb, 0, w, 0, 0, w, h)

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
        super.start()
        cameraControl.start()
    }

    override fun stop() {
        super.stop()
        cameraControl.stop()
    }

    override fun setPreviewView(previewView: PreviewView) {
        cameraControl.previewView = previewView
    }
}