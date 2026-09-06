package com.dsh.qwenbbox.ui;

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import com.dsh.qwenbbox.log.AppLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志页：查看内存日志全文，并支持「分享文件」（导出 .txt 到 Download/QwenBbox/ 后分享）。
 */
public class LogViewActivity extends Activity {

    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(this);
        title.setText("运行日志");
        title.setTextSize(20);
        title.setTextColor(0xFF3B2E5C);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("记录每次请求的端点/key 身份/HTTP 状态/返回摘要，便于排查。点「分享文件」可导出 .txt。");
        info.setTextSize(12);
        info.setTextColor(0xFF888888);
        info.setPadding(0, dp(6), 0, dp(10));
        root.addView(info);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button share = new Button(this);
        share.setText("分享文件");
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareFile(); }
        });
        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clear(); }
        });
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        share.setLayoutParams(bp); clear.setLayoutParams(bp); refresh.setLayoutParams(bp);
        btnRow.addView(share); btnRow.addView(clear); btnRow.addView(refresh);
        root.addView(btnRow);

        tv = new TextView(this);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(0xFF333333);
        tv.setBackgroundColor(0xFFFAFAFA);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(10);
        tv.setLayoutParams(tvLp);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        reload();
    }

    private void reload() {
        String txt = AppLog.dump();
        int n = lineCount(txt);
        tv.setText("（共 " + n + " 行）\n\n" + (txt == null || txt.isEmpty() ? "（暂无日志）" : txt));
    }

    /**
     * 分享日志为文本文件：Android 10+ 走 MediaStore 写入 Download/QwenBbox/ 并分享文件 URI；
     * 旧版本回退为纯文本分享。这样可直接把 .txt 文件发给助手排查。
     */
    private void shareFile() {
        String txt = AppLog.dump();
        if (txt == null || txt.isEmpty()) {
            Toast.makeText(this, "暂无日志可分享", Toast.LENGTH_SHORT).show();
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "qwenbbox_log_" + ts + ".txt";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/QwenBbox");
            Uri uri = null;
            try {
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            } catch (Exception e) {
                AppLog.e("LogView", "MediaStore 插入失败", e);
            }
            if (uri != null) {
                boolean ok = false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(txt.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (IOException e) {
                    AppLog.e("LogView", "写入分享文件失败", e);
                }
                if (ok) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.putExtra(Intent.EXTRA_SUBJECT, "QwenBbox 运行日志 " + fileName);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(intent, "分享日志文件"));
                        Toast.makeText(this,
                                "已导出到 Download/QwenBbox/" + fileName + " 并打开分享",
                                Toast.LENGTH_LONG).show();
                        return;
                    } catch (Exception e) {
                        AppLog.e("LogView", "打开分享面板失败", e);
                    }
                }
            }
            Toast.makeText(this, "文件导出失败，已回退为文本分享", Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "QwenBbox 运行日志 " + fileName);
        intent.putExtra(Intent.EXTRA_TEXT, txt);
        try {
            startActivity(Intent.createChooser(intent, "分享日志"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享方式", Toast.LENGTH_SHORT).show();
        }
    }

    private void clear() {
        AppLog.clear();
        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        reload();
    }

    private static int lineCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n); }
}
