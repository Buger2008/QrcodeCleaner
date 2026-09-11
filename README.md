# QrcodeCleaner 二维码相册清理

扫描手机相册，自动识别并删除所有包含二维码的图片。

## 功能

- **相册选择**：支持多选要清理的相册文件夹（通过 MediaStore 图库 API 获取，不扫盘），不选则默认全部相册
- **多线程扫描**：并行识别二维码，充分利用多核 CPU，相册图片多也不会卡
- **二维码识别**：ML Kit Barcode（首选，神经网络检测）+ ZXing 兜底
- **安全删除**：Android 11+ 走系统确认删除对话框；Android 10 及以下走 ContentResolver 直接删除
- **权限适配**：Android 13+ 读取媒体权限、Android 10~12 读写外部存储、Android 9 及以下兼容处理
- **低版本兼容**：minSdk 21，代码避开 API 24+ 默认方法，兼顾老设备

## 截图

> 待补充

## 技术栈

| 项目 | 说明 |
|------|------|
| 语言 | Java |
| 最低 SDK | 21（Android 5.0） |
| 目标 SDK | 34（Android 14） |
| 编译 SDK | 35 |
| 二维码识别 | ML Kit Barcode 17.3.0 + ZXing core 3.5.3 |
| UI | AndroidX AppCompat |

## 构建

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（需配置签名 keystore）
./gradlew assembleRelease
```

### 签名配置

在 `app/build.gradle` 中已配置 `signingConfigs.release`，keystore 文件放在项目根目录 `qrcode-release.jks`（已加入 `.gitignore`）。如需自行生成：

```bash
keytool -genkeypair -v \
  -keystore qrcode-release.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias release \
  -storepass <your_password> \
  -keypass <your_password>
```

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/qrcodecleaner/
│   ├── MainActivity.java    # 主界面：相册选择、扫描、删除
│   └── QRCodeScanner.java   # ZXing 二维码识别工具类
└── res/
    ├── layout/activity_main.xml
    └── values/strings.xml
```

## License

见 [LICENSE](LICENSE)
