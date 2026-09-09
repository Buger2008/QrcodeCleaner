package com.example.qrcodepicclean;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：一个"开始清理"按钮，点击后扫描相册中所有图片，
 * 用 ZXing 尝试识别二维码，识别成功即删除该图片。
 * 未授权时先请求权限；被永久拒绝时引导用户跳转系统设置。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSION = 100;
    private static final int REQ_DELETE = 101;

    private Button btnClean;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private TextView tvResult;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnClean = findViewById(R.id.btn_clean);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);

        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasPermission()) {
                    startCleaning();
                } else {
                    requestPermission();
                }
            }
        });
    }

    // ---------------- 权限处理 ----------------

    /** 根据系统版本返回需要的权限。 */
    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 ~ 12
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        } else {
            // Android 9 及以下：删除还需要写权限
            return new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
    }

    private boolean hasPermission() {
        for (String p : neededPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this, neededPermissions(), REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) {
            return;
        }
        if (hasPermission()) {
            startCleaning();
            return;
        }

        // 判断是否被勾选了"不再询问"
        boolean permanentlyDenied = false;
        for (String p : neededPermissions()) {
            boolean denied = ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED;
            if (denied && !ActivityCompat.shouldShowRequestPermissionRationale(this, p)) {
                permanentlyDenied = true;
                break;
            }
        }

        if (permanentlyDenied) {
            showGoToSettingsDialog();
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
        }
    }

    /** 权限被永久拒绝时，弹窗引导跳转系统设置。 */
    private void showGoToSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed_title)
                .setMessage(R.string.permission_needed_msg)
                .setPositiveButton(R.string.go_settings,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 跳转到本应用详情设置页，让用户手动开启权限。 */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    // ---------------- 清理流程 ----------------

    private void startCleaning() {
        btnClean.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        tvResult.setText("");
        tvStatus.setText(R.string.scanning);

        executor.execute(() -> {
            List<Uri> qrUris = scanAndCollect();
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                btnClean.setEnabled(true);
                if (qrUris.isEmpty()) {
                    tvStatus.setVisibility(View.GONE);
                    tvResult.setText(R.string.no_qr_found);
                } else {
                    tvStatus.setText(getString(R.string.found_qr, qrUris.size()));
                    deleteImages(qrUris);
                }
            });
        });
    }

    /** 后台扫描相册，返回所有识别出二维码的图片 Uri。 */
    private List<Uri> scanAndCollect() {
        List<Uri> qrUris = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        String[] projection = {MediaStore.Images.Media._ID};

        try (Cursor cursor = cr.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null)) {
            if (cursor == null) {
                return qrUris;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            final int total = cursor.getCount();
            int index = 0;

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        String.valueOf(id));

                String text = QRCodeScanner.decodeQrFromUri(this, uri);
                if (text != null) {
                    qrUris.add(uri);
                }

                index++;
                // 节流刷新进度
                if (index % 10 == 0 || index == total) {
                    final int current = index;
                    runOnUiThread(() ->
                            tvStatus.setText(getString(R.string.scanning_progress, current, total)));
                }
            }
        } catch (Exception e) {
            // 查询失败时静默返回已识别的结果
        }
        return qrUris;
    }

    /** 根据系统版本选择删除方式。 */
    private void deleteImages(List<Uri> uris) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：走系统确认删除对话框（无需额外写权限）
            try {
                PendingIntent deleteRequest =
                        MediaStore.createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(
                        deleteRequest.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                return;
            } catch (Exception e) {
                // 系统不支持时逐条尝试
            }
        }
        // Android 10 及以下：直接删除
        for (Uri uri : uris) {
            deleteSingle(uri);
        }
        finishCleaning(uris.size());
    }

    /** 删除单张图片，先走 ContentResolver，失败则回退到 File 删除（仅旧系统）。 */
    private void deleteSingle(Uri uri) {
        try {
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) {
                return;
            }
        } catch (Exception ignored) {
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            String path = getRealPath(uri);
            if (path != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(path).delete();
            }
        }
    }

    private String getRealPath(Uri uri) {
        try (Cursor c = getContentResolver().query(
                uri,
                new String[]{MediaStore.Images.Media.DATA},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void finishCleaning(int count) {
        tvStatus.setVisibility(View.GONE);
        String msg = getString(R.string.cleaned, count);
        tvResult.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DELETE) {
            return;
        }
        tvStatus.setVisibility(View.GONE);
        if (resultCode == RESULT_OK) {
            tvResult.setText(R.string.delete_confirmed);
            Toast.makeText(this, R.string.delete_confirmed, Toast.LENGTH_LONG).show();
        } else {
            tvResult.setText(R.string.delete_cancelled);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
