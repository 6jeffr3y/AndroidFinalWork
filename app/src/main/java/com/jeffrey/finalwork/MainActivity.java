package com.jeffrey.finalwork;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.jeffrey.finalwork.net.TencentOcrClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private ImageView img;
    private TextView txtResult;
    private TextView txtRisk;
    private TextView watermark;
    private View maskLayer;

    // 预览折叠相关
    private View previewContent;
    private TextView previewToggle;
    private boolean previewExpanded = true;

    private Button btnCopy;   // 复制按钮
    private Button btnReveal; // 查看明文按钮

    private Uri photoUri;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TencentOcrClient client;
    private TencentOcrClient.OcrResult lastResult;
    private boolean revealed = false;

    private final ActivityResultLauncher<String> requestCameraPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) takePhoto();
                else toast("未授予相机权限");
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (!success) {
                    toast("拍照失败/取消");
                    return;
                }
                if (img != null) img.setImageURI(photoUri);

                // 拍到照片时，若预览折叠则自动展开
                if (!previewExpanded) {
                    previewExpanded = true;
                    applyPreviewState(true);
                }

                doOcr(photoUri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 防截屏/防最近任务泄露
        //getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
        //        WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_main);

        // Edge-to-Edge：异常则降级，不闪退
        enableEdgeToEdgeSafely();

        // 恢复预览折叠状态
        if (savedInstanceState != null) {
            previewExpanded = savedInstanceState.getBoolean("previewExpanded", true);
        }

        // 绑定控件
        img = findViewById(R.id.img);
        txtResult = findViewById(R.id.txtResult);
        txtRisk = findViewById(R.id.txtRisk);
        watermark = findViewById(R.id.watermark);
        maskLayer = findViewById(R.id.maskLayer);

        Button btnCapture = findViewById(R.id.btnCapture);
        btnReveal = findViewById(R.id.btnReveal);
        btnCopy = findViewById(R.id.btnCopyMasked);

        // 预览折叠控件
        View previewHeader = findViewById(R.id.previewHeader);
        previewContent = findViewById(R.id.previewContent);
        previewToggle = findViewById(R.id.previewToggle);
        applyPreviewState(false);
        if (previewHeader != null) {
            previewHeader.setOnClickListener(v -> {
                previewExpanded = !previewExpanded;
                applyPreviewState(true);
            });
        }

        // 禁止长按复制（通过按钮复制，避免误操作泄露）
        if (txtResult != null) {
            txtResult.setTextIsSelectable(false);
            txtResult.setLongClickable(false);
            txtResult.setOnLongClickListener(v -> true);
        }

        client = new TencentOcrClient(Secrets.SECRET_ID, Secrets.SECRET_KEY, Secrets.REGION);
        setWatermark();
        updateCopyButtonText();

        btnCapture.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestCameraPerm.launch(Manifest.permission.CAMERA);
            } else {
                takePhoto();
            }
        });

        btnReveal.setOnClickListener(v -> {
            if (lastResult == null) {
                toast("还没有识别结果");
                return;
            }
            if (revealed) {
                // 再点一次：回退脱敏
                revealed = false;
                showMasked(lastResult);
                toast("已恢复脱敏显示");
                updateCopyButtonText();
                btnReveal.setText("查看明文");
                return;
            }
            biometricUnlockThenReveal(lastResult);
        });

        btnCopy.setOnClickListener(v -> {
            if (lastResult == null) {
                toast("还没有识别结果");
                return;
            }

            // 核心：复制内容不附加任何额外文字，只复制“纯内容”
            String toCopy = revealed ? buildPlainText(lastResult) : buildMaskedText(lastResult);

            boolean ok = copyToClipboard(revealed ? "明文" : "脱敏", toCopy);
            if (ok) {
                toast(revealed ? "明文已复制" : "脱敏内容已复制");
                buttonCopiedFeedback();
            } else {
                toast("复制失败");
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean("previewExpanded", previewExpanded);
        super.onSaveInstanceState(outState);
    }

    // 切后台遮罩，回前台恢复
    @Override protected void onPause() {
        super.onPause();
        if (maskLayer != null) maskLayer.setVisibility(View.VISIBLE);
    }

    @Override protected void onResume() {
        super.onResume();
        if (maskLayer != null) maskLayer.setVisibility(View.GONE);
    }

    private void enableEdgeToEdgeSafely() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            final View root = findViewById(R.id.root);
            if (root != null) {
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                });
            }
        } catch (Throwable ignored) {}
    }

    private void applyPreviewState(boolean animate) {
        if (previewContent == null || previewToggle == null) return;

        if (!animate) {
            previewContent.setVisibility(previewExpanded ? View.VISIBLE : View.GONE);
            previewToggle.setText(previewExpanded ? "收起 ▲" : "展开 ▼");
            return;
        }

        if (previewExpanded) {
            previewContent.setAlpha(0f);
            previewContent.setVisibility(View.VISIBLE);
            previewContent.animate().alpha(1f).setDuration(180).start();
            previewToggle.setText("收起 ▲");
        } else {
            previewContent.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                previewContent.setVisibility(View.GONE);
                previewContent.setAlpha(1f);
            }).start();
            previewToggle.setText("展开 ▼");
        }
    }

    private void setWatermark() {
        if (watermark == null) return;
        long ts = System.currentTimeMillis();
        watermark.setText(String.format(Locale.US, "user=jeffrey  ts=%d", ts));
    }

    private void takePhoto() {
        try {
            File photoFile = File.createTempFile("idcard_", ".jpg", getCacheDir());
            photoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photoFile
            );
            takePictureLauncher.launch(photoUri);
        } catch (Exception e) {
            toast("创建照片文件失败: " + e.getMessage());
        }
    }

    private void doOcr(@NonNull Uri uri) {
        if (txtResult != null) txtResult.setText("识别中...");

        final String base64;
        try {
            base64 = readUriToBase64(getContentResolver(), uri);
        } catch (Exception e) {
            toast("Base64转换失败: " + e.getMessage());
            return;
        }

        // 识别开始时先回到“脱敏状态”
        revealed = false;
        if (btnReveal != null) btnReveal.setText("查看明文");
        updateCopyButtonText();

        client.idCardOcr(base64, "FRONT", "{\"CropIdCard\":true,\"CropPortrait\":true}", new TencentOcrClient.Callback() {
            @Override public void onSuccess(TencentOcrClient.OcrResult result) {
                ui.post(() -> {
                    lastResult = result;

                    int score = riskScore(result);
                    if (txtRisk != null) {
                        txtRisk.setText("风险评分：" + score + "（越高越敏感，建议不要外发/截图）");
                    }

                    showMasked(result);
                    toast("识别成功（默认脱敏显示）");

                    // 日志只输出脱敏
                    android.util.Log.d("OCR", redactDigits(result.rawJson));
                });
            }

            @Override public void onError(String msg) {
                ui.post(() -> toast("请求失败: " + msg));
            }
        });
    }

    // ========= 安全显示：默认脱敏 / 解锁明文10秒 =========

    private void showMasked(TencentOcrClient.OcrResult r) {
        if (txtResult != null) txtResult.setText(buildMaskedText(r));
        updateCopyButtonText();
    }

    private void showPlainTemporarily(TencentOcrClient.OcrResult r) {
        revealed = true;
        if (txtResult != null) txtResult.setText(buildPlainText(r));
        if (btnReveal != null) btnReveal.setText("恢复脱敏");
        updateCopyButtonText();

        toast("明文已解锁（10秒后自动恢复脱敏）");

        if (txtResult != null) {
            txtResult.removeCallbacks(revertRunnable);
            txtResult.postDelayed(revertRunnable, 10_000);
        }
    }

    private final Runnable revertRunnable = () -> {
        revealed = false;
        if (btnReveal != null) btnReveal.setText("查看明文");
        if (lastResult != null) showMasked(lastResult);
    };

    private void biometricUnlockThenReveal(TencentOcrClient.OcrResult r) {
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        showPlainTemporarily(r);
                    }

                    @Override public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        toast("解锁失败/取消：" + errString);
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("查看明文需要解锁")
                .setSubtitle("为防敏感信息泄露，明文仅展示10秒")
                .setAllowedAuthenticators(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build();

        prompt.authenticate(info);
    }

    // ========= 复制逻辑：复制纯内容 + 反馈 =========

    private void updateCopyButtonText() {
        if (btnCopy == null) return;
        btnCopy.setText(revealed ? "复制明文" : "复制脱敏");
    }

    private boolean copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void buttonCopiedFeedback() {
        if (btnCopy == null) return;
        final CharSequence old = btnCopy.getText();
        btnCopy.setText("已复制");
        btnCopy.postDelayed(() -> {
            // 恢复到当前状态应显示的文字
            updateCopyButtonText();
        }, 900);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ========= 文本构造：注意“复制内容不加额外文字” =========

    private String buildMaskedText(TencentOcrClient.OcrResult r) {
        // 这里用于“显示”和“复制脱敏”（都是脱敏版）
        return "姓名：" + maskName(r.name) + "\n" +
                "身份证号：" + maskId(r.idNumber) + "\n" +
                "住址：" + maskAddress(r.address) + "\n" +
                "性别：" + safe(r.sex) + "\n" +
                "民族：" + safe(r.nation) + "\n" +
                "出生：" + safe(r.birth);
    }

    private String buildPlainText(TencentOcrClient.OcrResult r) {
        // 这里用于“显示明文”和“复制明文”（纯内容，不加提示语）
        return "姓名：" + safe(r.name) + "\n" +
                "身份证号：" + safe(r.idNumber) + "\n" +
                "住址：" + safe(r.address) + "\n" +
                "性别：" + safe(r.sex) + "\n" +
                "民族：" + safe(r.nation) + "\n" +
                "出生：" + safe(r.birth);
    }

    // ========= 工具：Base64 / 脱敏 / 风险评分 / 日志脱敏 =========

    private static String readUriToBase64(ContentResolver cr, Uri uri) throws Exception {
        try (InputStream is = cr.openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is == null) throw new IllegalStateException("openInputStream returned null");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        }
    }

    private static int riskScore(TencentOcrClient.OcrResult r) {
        int s = 0;
        if (r.name != null && !r.name.isEmpty()) s += 20;
        if (r.idNumber != null && r.idNumber.length() >= 15) s += 55;
        if (r.address != null && r.address.length() >= 6) s += 25;
        return Math.min(100, s);
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) return "";
        if (name.length() == 1) return "*";
        return name.charAt(0) + "*".repeat(Math.max(1, name.length() - 1));
    }

    private static String maskId(String id) {
        if (id == null) return "";
        String s = id.trim();
        if (s.length() < 8) return "***";
        return s.substring(0, 6) + "******" + s.substring(s.length() - 4);
    }

    private static String maskAddress(String addr) {
        if (addr == null) return "";
        String s = addr.trim();
        if (s.length() <= 6) return s.isEmpty() ? "" : (s.charAt(0) + "***");
        return s.substring(0, 6) + "***";
    }

    private static String redactDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("\\d{6,}", "******");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}