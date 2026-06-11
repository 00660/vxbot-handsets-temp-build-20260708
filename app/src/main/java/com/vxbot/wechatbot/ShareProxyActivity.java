package com.vxbot.wechatbot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;

public final class ShareProxyActivity extends Activity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_FILE_NAME = "fileName";
    public static final String EXTRA_FILE_PATH = "filePath";
    public static final String EXTRA_DIRECT = "direct";
    public static final String EXTRA_PREFIX = "prefix";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Handler(Looper.getMainLooper()).postDelayed(this::launchWechatShare, 120);
    }

    private void launchWechatShare() {
        try {
            Intent source = getIntent();
            Uri uri = source.getParcelableExtra(EXTRA_URI);
            String filePath = source.getStringExtra(EXTRA_FILE_PATH);
            String mime = source.getStringExtra(EXTRA_MIME);
            String fileName = source.getStringExtra(EXTRA_FILE_NAME);
            String prefix = source.getStringExtra(EXTRA_PREFIX);
            boolean direct = source.getBooleanExtra(EXTRA_DIRECT, true);
            if (uri == null && filePath != null && !filePath.trim().isEmpty()) {
                uri = Uri.fromFile(new File(filePath));
            }
            if (uri == null) {
                throw new IllegalStateException("uri missing");
            }
            if (mime == null || mime.trim().isEmpty()) {
                mime = "image/*";
            }
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "image";
            }
            allowFileUriIfNeeded(uri);
            Intent share = new Intent(Intent.ACTION_SEND);
            if (direct) {
                share.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
                share.addCategory(Intent.CATEGORY_DEFAULT);
            } else {
                share.setPackage("com.tencent.mm");
            }
            share.setDataAndType(uri, mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, fileName);
            share.putExtra(Intent.EXTRA_TITLE, fileName);
            share.setClipData(ClipData.newUri(getContentResolver(), fileName, uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("content".equals(uri.getScheme())) {
                grantUriPermission("com.tencent.mm", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startActivity(share);
            BotLog.i(this, "image.share.proxy.start", "代理 Activity 已启动微信分享 direct=" + direct
                    + " uriScheme=" + uri.getScheme() + " mime=" + mime + " prefix=" + prefix);
        } catch (Exception e) {
            BotLog.e(this, "image.share.proxy.error", "代理 Activity 启动微信分享失败: " + e.getMessage());
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void allowFileUriIfNeeded(Uri uri) {
        if (!"file".equals(uri.getScheme())) {
            return;
        }
        try {
            Class<?> strictMode = Class.forName("android.os.StrictMode");
            Method method = strictMode.getMethod("disableDeathOnFileUriExposure");
            method.invoke(null);
        } catch (Exception ignored) {
        }
    }
}
