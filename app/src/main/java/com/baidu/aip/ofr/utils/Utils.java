package com.baidu.aip.ofr.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;

import com.baidu.aip.entity.ARGBImg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by litonghui on 2018/5/11.
 */

public class Utils {
    public static final String TAG = "file-face";

    public static boolean saveBitmapToFile(String savePath, Bitmap bitmap) {
        boolean result = false;
        FileOutputStream out = null;

        File dir = new File(Environment.getExternalStorageDirectory() + File.separator + "baidu/BDFace");
        if (!dir.exists()) {
            dir.mkdir();
        }
        File f = new File(dir, savePath);
        if (f.exists()) {
            f.delete();
        }
        try {
            out = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
            result = true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }

    public static boolean saveStringToFile(String savePath, String landmark) {
        boolean result = false;
        FileOutputStream out = null;

        File dir = new File(Environment.getExternalStorageDirectory() + File.separator + "BDFace");
        if (!dir.exists()) {
            dir.mkdir();
        }
        File f = new File(dir, savePath);
        if (f.exists()) {
            f.delete();
        }
        try {
            out = new FileOutputStream(f);
            out.write(landmark.getBytes());
            result = true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }

    public static String saveToFile(File dir, String fileName, byte[] content) {
        try {
            if (!dir.exists()) {
                dir.mkdir();
            }
            File file = new File(dir, fileName);
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content);
            fos.close();
            return file.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] getFromFile(String fileName) {
        byte[] content = new byte[2048];
        File file = new File(fileName);
        try {
            FileInputStream stream = new FileInputStream(file);
            stream.read(content);
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return content;
    }

    public static Bitmap getBitmapFromFile(String fileName) {
        File file = new File(fileName);
        try {
            FileInputStream stream = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            return bitmap;

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ARGBImg getARGB(Bitmap bitmap) {
        if (bitmap != null) {
            int[] argbData = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(argbData, 0, bitmap.getWidth(),
                    0, 0, bitmap.getWidth(), bitmap.getHeight());
            ARGBImg argbImg = new ARGBImg(argbData, bitmap.getWidth(),
                    bitmap.getHeight(), 0, 0);
            return argbImg;
        }
        return null;
    }

    /**
     * 打印flot数组
     *
     * @param floats
     */
    public static void logByteArray(byte[] floats) {
        StringBuilder featureInfo = new StringBuilder();
        featureInfo.setLength(0);
        for (int i = 0; i < floats.length / 4; i = i + 4) {

            float f = (((int) floats[i]) << 24) + (((int) floats[i + 1]) << 16)
                    + (((int) floats[i + 2]) << 8) + floats[i + 3];

            featureInfo.append("[").append(i).append("] =").append(f).append(",");
        }
        Log.w("facefeature", featureInfo.toString());
    }

    private static String byteToBit(byte b) {
        return ""
                + (byte) ((b >> 7) & 0x1) + (byte) ((b >> 6) & 0x1)
                + (byte) ((b >> 5) & 0x1) + (byte) ((b >> 4) & 0x1)
                + (byte) ((b >> 3) & 0x1) + (byte) ((b >> 2) & 0x1)
                + (byte) ((b >> 1) & 0x1) + (byte) ((b >> 0) & 0x1);
    }
}
