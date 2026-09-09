package com.example.qrcodepicclean;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码识别工具类。
 * 原理：把相册图片解码成 Bitmap，再用 ZXing 尝试扫码，
 * 只要能成功识别出二维码内容，就认为这张图"带二维码"。
 */
public final class QRCodeScanner {

    /** 解码时的最大尺寸，避免大图导致内存溢出。 */
    private static final int MAX_DIMENSION = 2048;

    private QRCodeScanner() {
    }

    /**
     * 从 content:// 类型的 URI 读取图片并尝试识别二维码。
     *
     * @return 识别成功返回二维码内容，失败返回 null
     */
    public static String decodeQrFromUri(Context context, Uri uri) {
        Bitmap bitmap = loadScaledBitmap(context, uri);
        if (bitmap == null) {
            return null;
        }
        try {
            return decodeQr(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** 按比例缩小后加载图片，控制内存占用。 */
    private static Bitmap loadScaledBitmap(Context context, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    return null;
                }
                BitmapFactory.decodeStream(is, null, bounds);
            }

            int sample = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) {
                return null;
            }
            while (w / sample > MAX_DIMENSION || h / sample > MAX_DIMENSION) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 用 ZXing 对 Bitmap 做二维码识别。 */
    private static String decodeQr(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));

        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        try {
            Result result = reader.decode(binaryBitmap);
            return result == null ? null : result.getText();
        } catch (Exception e) {
            // 没有二维码或识别失败
            return null;
        } finally {
            reader.reset();
        }
    }
}
