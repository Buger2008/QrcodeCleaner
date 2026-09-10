package com.example.qrcodepicclean;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
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
import java.util.List;
import java.util.Map;

/**
 * 二维码识别工具类。
 * 首选 ML Kit BarcodeScanning（内嵌模型，GPU/NNAPI 加速，不依赖 Play Services），
 * ML Kit 未识别时回退 ZXing 兜底，确保最大覆盖率。
 */
public final class QRCodeScanner {

    /** 解码时的最大尺寸：二维码识别不需要太高分辨率，缩到 1600 足以识别且大幅提速。 */
    private static final int MAX_DIMENSION = 1600;

    /** ML Kit 扫码器单例，避免每张图重复初始化模型。 */
    private static volatile BarcodeScanner sMlScanner;

    private QRCodeScanner() {
    }

    /** 释放 ML Kit 扫码器资源（Activity 销毁时调用）。 */
    public static void shutdown() {
        synchronized (QRCodeScanner.class) {
            if (sMlScanner != null) {
                sMlScanner.close();
                sMlScanner = null;
            }
        }
    }

    private static BarcodeScanner getMlScanner() {
        if (sMlScanner == null) {
            synchronized (QRCodeScanner.class) {
                if (sMlScanner == null) {
                    BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build();
                    sMlScanner = BarcodeScanning.getClient(options);
                }
            }
        }
        return sMlScanner;
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
            // 第一梯队：ML Kit（神经网络检测，快且准）
            String text = decodeWithMlKit(bitmap);
            if (text != null) {
                return text;
            }
            // 第二梯队：ZXing 兜底（ML Kit 模型加载失败或漏检时）
            return decodeWithZxing(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** 用 ML Kit BarcodeScanning 识别（同步阻塞，在工作线程调用）。 */
    private static String decodeWithMlKit(Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            List<Barcode> barcodes = Tasks.await(getMlScanner().process(image));
            if (barcodes != null && !barcodes.isEmpty()) {
                String raw = barcodes.get(0).getRawValue();
                if (raw != null && !raw.isEmpty()) {
                    return raw;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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

    /** 用 ZXing 对 Bitmap 做二维码识别（ML Kit 失败时的兜底）。 */
    private static String decodeWithZxing(Bitmap bitmap) {
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
