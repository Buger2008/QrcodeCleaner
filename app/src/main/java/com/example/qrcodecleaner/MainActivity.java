package com.example.qrcodecleaner;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主界面：一个"开始清理"按钮，点击后扫描相册中所有图片，
 * 用 ML Kit / ZXing 尝试识别二维码，识别成功即删除该图片。
 * 未授权时先请求权限；被永久拒绝时引导用户跳转系统设置。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSION = 100;
    private static final int REQ_DELETE = 101;

    private static final String PREFS_NAME = "scan_settings";
    private static final String KEY_SELECTED_BUCKETS = "selected_bucket_ids";
    private static final String KEY_RESOLUTION_FILTER = "resolution_filter";

    /** 本次权限请求是否由"选择相册"按钮触发（授权后回到相册选择而非直接清理）。 */
    private boolean pendingAlbumPick = false;

    private Button btnClean;
    private Button btnPickAlbums;
    private TextView tvScope;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private TextView tvResult;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 并行扫描图片的线程池。 */
    private final ExecutorService scanPool = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnClean = findViewById(R.id.btn_clean);
        btnPickAlbums = findViewById(R.id.btn_pick_albums);
        tvScope = findViewById(R.id.tv_scope);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);

        btnPickAlbums.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasPermission()) {
                    showAlbumPicker();
                } else {
                    pendingAlbumPick = true;
                    requestPermission();
                }
            }
        });

        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasPermission()) {
                    startCleaning();
                } else {
                    pendingAlbumPick = false;
                    requestPermission();
                }
            }
        });

        updateScopeLabel();
    }

    // ---------------- 菜单与关于 ----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 弹出"关于"对话框，展示软件版本等信息。 */
    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, getVersionName()))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** 从 PackageManager 读取当前应用版本号。 */
    private String getVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
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
            if (pendingAlbumPick) {
                pendingAlbumPick = false;
                showAlbumPicker();
            } else {
                startCleaning();
            }
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

    // ---------------- 筛选设置（相册 + 分辨率） ----------------

    /** 当前选中的相册 bucket id 集合；空集合表示全部相册。 */
    private Set<String> getSelectedBuckets() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_SELECTED_BUCKETS,
                Collections.<String>emptySet()));
    }

    private void saveSelectedBuckets(Set<String> bucketIds) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SELECTED_BUCKETS, bucketIds)
                .apply();
    }

    /** 当前分辨率筛选表达式（空字符串=不限制）。 */
    private String getResolutionFilter() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_RESOLUTION_FILTER, "");
    }

    private void saveResolutionFilter(String filter) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_RESOLUTION_FILTER, filter)
                .apply();
    }

    /** 用 MediaStore 的相册分组查询列出所有相册（不扫盘）。返回按名称排序的 {bucketId, 名称, 图片数} 列表。 */
    private List<String[]> loadAlbums() {
        Map<String, String> bucketMap = new LinkedHashMap<>();
        Map<String, Integer> countMap = new LinkedHashMap<>();
        String[] projection = {
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    String id = cursor.getString(idCol);
                    String name = cursor.getString(nameCol);
                    if (id != null && name != null) {
                        if (!bucketMap.containsKey(id)) {
                            bucketMap.put(id, name);
                            countMap.put(id, 1);
                        } else {
                            countMap.put(id, countMap.get(id) + 1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<String[]> albums = new ArrayList<>();
        for (Map.Entry<String, String> e : bucketMap.entrySet()) {
            albums.add(new String[]{e.getKey(), e.getValue(),
                    String.valueOf(countMap.get(e.getKey()))});
        }
        // 用 Collections.sort：List.sort 是 API 24+ 才有的默认方法，低版本设备会挂
        Collections.sort(albums, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return albums;
    }

    /** 后台加载相册列表，完成后弹出筛选对话框。 */
    private void showAlbumPicker() {
        if (!hasPermission()) {
            pendingAlbumPick = true;
            requestPermission();
            return;
        }
        btnPickAlbums.setEnabled(false);
        executor.execute(() -> {
            final List<String[]> albums = loadAlbums();
            runOnUiThread(() -> {
                btnPickAlbums.setEnabled(true);
                if (isFinishing()) {
                    return;
                }
                showFilterDialog(albums);
            });
        });
    }

    /** 弹出合并筛选对话框（相册多选 + 分辨率 ComboBox）。 */
    private void showFilterDialog(List<String[]> albums) {
        if (albums.isEmpty()) {
            Toast.makeText(this, R.string.album_dialog_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        // 自定义布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_filter, null);
        android.widget.LinearLayout albumContainer =
                dialogView.findViewById(R.id.album_container);
        AutoCompleteTextView actvRes = dialogView.findViewById(R.id.actv_resolution);

        // --- 相册 checkbox 列表 ---
        final int size = albums.size();
        final boolean[] checked = new boolean[size];
        final Set<String> current = getSelectedBuckets();
        for (int i = 0; i < size; i++) {
            String[] album = albums.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setText(getString(R.string.album_item, album[1],
                    Integer.parseInt(album[2])));
            final int idx = i;
            checked[i] = current.isEmpty() || current.contains(album[0]);
            cb.setChecked(checked[i]);
            cb.setOnCheckedChangeListener((button, isChecked) -> checked[idx] = isChecked);
            albumContainer.addView(cb);
        }

        // --- 分辨率 AutoCompleteTextView（可选可输入） ---
        String[] resPresets = {
                getString(R.string.res_no_limit),
                getString(R.string.res_le_480),
                getString(R.string.res_480_720),
                getString(R.string.res_720_1080),
                getString(R.string.res_ge_1080),
        };
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, resPresets);
        actvRes.setAdapter(resAdapter);
        actvRes.setThreshold(0);
        // 恢复上次的筛选值
        String savedRes = getResolutionFilter();
        if (!savedRes.isEmpty()) {
            actvRes.setText(savedRes);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.filter_dialog_title)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    // 保存相册选择
                    Set<String> selected = new HashSet<>();
                    for (int i = 0; i < size; i++) {
                        if (checked[i]) {
                            selected.add(albums.get(i)[0]);
                        }
                    }
                    saveSelectedBuckets(selected);
                    // 保存分辨率选择（原样存储用户输入/选择的文本）
                    saveResolutionFilter(actvRes.getText().toString().trim());
                    updateScopeLabel();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 刷新当前扫描范围提示文字。 */
    private void updateScopeLabel() {
        int count = getSelectedBuckets().size();
        String resText = getResolutionFilter();
        String resLabel = resText.isEmpty()
                ? getString(R.string.res_no_limit) : resText;
        if (count == 0) {
            tvScope.setText(getString(R.string.scope_all));
        } else {
            tvScope.setText(getString(R.string.scope_selected, count, resLabel));
        }
    }

    // ---------------- 清理流程 ----------------

    /** 扫描结果项：识别出二维码的图片 Uri。 */
    private static class ScanResult {
        final Uri uri;
        ScanResult(Uri uri) {
            this.uri = uri;
        }
    }

    private void startCleaning() {
        btnClean.setEnabled(false);
        btnPickAlbums.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        tvResult.setText("");
        tvStatus.setText(R.string.scanning);

        executor.execute(() -> {
            final List<ScanResult> results = scanAndCollect();
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                btnClean.setEnabled(true);
                btnPickAlbums.setEnabled(true);
                if (results.isEmpty()) {
                    tvStatus.setVisibility(View.GONE);
                    tvResult.setText(R.string.no_qr_found);
                } else {
                    tvStatus.setText(getString(R.string.found_qr, results.size()));
                    showDeleteConfirmDialog(results);
                }
            });
        });
    }

    /** 后台并行扫描所选相册+分辨率范围，返回所有识别出二维码的图片。 */
    private List<ScanResult> scanAndCollect() {
        ContentResolver cr = getContentResolver();
        String[] projection = {
                MediaStore.Images.Media._ID
        };

        // 组合查询条件：相册 + 分辨率
        List<String> conditions = new ArrayList<>();
        List<String> args = new ArrayList<>();

        // 相册筛选
        Set<String> bucketIds = getSelectedBuckets();
        if (!bucketIds.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    MediaStore.Images.Media.BUCKET_ID + " IN (");
            boolean first = true;
            for (String id : bucketIds) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('?');
                args.add(id);
                first = false;
            }
            sb.append(')');
            conditions.add(sb.toString());
        }

        // 分辨率筛选（基于 WIDTH 列），解析用户输入的表达式
        String resFilter = getResolutionFilter();
        if (!resFilter.isEmpty()) {
            String w = MediaStore.Images.Media.WIDTH;
            // 统一全角→半角
            String expr = resFilter.replace('≤', '<')
                                   .replace('≥', '>')
                                   .replace('~', '-')
                                   .replace('～', '-');
            expr = expr.replace("宽度", "").trim();

            if (expr.equals(getString(R.string.res_no_limit))) {
                // 不限制，不加条件
            } else if (expr.matches("^<=\\s*\\d+$")) {
                // 宽度 ≤ 480 → WIDTH <= 480
                String num = expr.replace("<=", "").trim();
                conditions.add(w + " <= " + num);
            } else if (expr.matches("^>=\\s*\\d+$")) {
                // 宽度 ≥ 1080 → WIDTH > 1080
                String num = expr.replace(">=", "").trim();
                conditions.add(w + " > " + num);
            } else if (expr.matches("^\\d+\\s*-\\s*\\d+$")) {
                // 宽度 480-720 → WIDTH > 480 AND WIDTH <= 720--720
                String[] parts = expr.split("-");
                String lo = parts[0].trim();
                String hi = parts[1].trim();
                conditions.add(w + " > " + lo);
                conditions.add(w + " <= " + hi);
            } else if (expr.matches("^\\d+$")) {
                // 纯数字 → 精确匹配
                conditions.add(w + " = " + expr);
            }
        }

        String selection = null;
        String[] selectionArgs = null;
        if (!conditions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < conditions.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                sb.append(conditions.get(i));
            }
            selection = sb.toString();
            selectionArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        }

        // 第一步：只查 id（不取名，省一列查询和一堆字符串内存）
        final List<Long> ids = new ArrayList<>();
        try (Cursor cursor = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null)) {
            if (cursor == null) {
                return new ArrayList<>();
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idCol));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

        // 第二步：并行识别二维码
        final int total = ids.size();
        final List<ScanResult> qrResults = Collections.synchronizedList(new ArrayList<ScanResult>());
        final AtomicInteger done = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(total);

        for (int i = 0; i < total; i++) {
            final long id = ids.get(i);
            final Uri uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    String.valueOf(id));
            scanPool.execute(() -> {
                try {
                    String text = QRCodeScanner.decodeQrFromUri(this, uri);
                    if (text != null) {
                        qrResults.add(new ScanResult(uri));
                    }
                } finally {
                    int current = done.incrementAndGet();
                    // 节流刷新进度（10 张一次，避免频繁刷新 UI）
                    if (current % 10 == 0 || current == total) {
                        runOnUiThread(() ->
                                tvStatus.setText(getString(
                                        R.string.scanning_progress, current, total)));
                    }
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ArrayList<>(qrResults);
    }

    /** 扫描完成后弹多选确认对话框（带缩略图），让用户选择要删除的图片。 */
    private void showDeleteConfirmDialog(List<ScanResult> results) {
        // 自定义布局 + CheckBox + 缩略图
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_delete_confirm, null);
        android.widget.LinearLayout container =
                dialogView.findViewById(R.id.delete_container);

        final int size = results.size();
        final boolean[] checked = new boolean[size];
        for (int i = 0; i < size; i++) {
            View item = getLayoutInflater().inflate(
                    R.layout.dialog_delete_item, container, false);
            ImageView ivThumb = item.findViewById(R.id.iv_thumb);
            CheckBox cb = item.findViewById(R.id.cb_delete);

            final int idx = i;
            checked[i] = true;
            cb.setChecked(true);
            cb.setOnCheckedChangeListener((button, isChecked) -> checked[idx] = isChecked);
            // 整行可点：点缩略图也能切换勾选，不用非去点小方框
            item.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            // 后台加载缩略图
            loadThumbnail(results.get(i).uri, ivThumb);

            container.addView(item);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_confirm_title, size))
                .setView(dialogView)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    List<Uri> toDelete = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        if (checked[i]) {
                            toDelete.add(results.get(i).uri);
                        }
                    }
                    if (toDelete.isEmpty()) {
                        tvStatus.setVisibility(View.GONE);
                        tvResult.setText(R.string.delete_cancelled);
                    } else {
                        deleteImages(toDelete);
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    tvStatus.setVisibility(View.GONE);
                    tvResult.setText(R.string.delete_cancelled);
                })
                .show();
    }

    /** 后台加载图片缩略图到指定 ImageView。 */
    private void loadThumbnail(final Uri uri, final ImageView iv) {
        scanPool.execute(() -> {
            Bitmap thumb = decodeThumbnail(uri);
            runOnUiThread(() -> {
                if (thumb != null) {
                    iv.setImageBitmap(thumb);
                }
            });
        });
    }

    /**
     * 解码缩略图（最长边约 400px）。
     * inSampleSize 只能取 2 的幂，所以目标值要留足余量，否则大图会被降过头导致发糊。
     * 用 ARGB_8888 而非 RGB_565，避免照片出现色带。
     */
    private Bitmap decodeThumbnail(Uri uri) {
        final int maxDim = 400;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
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
            while (w / sample > maxDim || h / sample > maxDim) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception ignored) {
        }
        return null;
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
        scanPool.shutdownNow();
        QRCodeScanner.shutdown();
    }
}
