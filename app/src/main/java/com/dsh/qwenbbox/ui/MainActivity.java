package com.dsh.qwenbbox.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.qwenbbox.ProviderConfig;
import com.dsh.qwenbbox.ProviderStore;
import com.dsh.qwenbbox.SettingsStore;
import com.dsh.qwenbbox.TemplateStore;
import com.dsh.qwenbbox.api.VisionApiClient;
import com.dsh.qwenbbox.core.BboxParser;
import com.dsh.qwenbbox.log.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;

/**
 * 主页（QwenBboxDraw）：忠实移植 qwen3-vl-2d.py 的流程，
 * 但模型换成 qwen3.8-flash、图片改为本地选图（base64 直传，对应 Python 的 URL 下载）。
 *
 * <p>流程：选图 → 发提示词给 qwen3.8-flash（百炼 OpenAI 兼容，流式）→ 解析返回里的
 * {@code bbox_2d: [x1,y1,x2,y2]}（归一化到 [0,1000]）→ 按 Python {@code plot_bounding_boxes}
 * 的算法 {@code abs = bbox_2d[i] / 1000 * width/height} 画带颜色+标签的框 → 展示原图与打框图。</p>
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    private byte[] jpegBytes;        // 送入模型的 JPEG（原图直传，不缩放）
    private int imgW, imgH;          // 原图实际像素宽高
    private Bitmap origBmp;          // 原图

    private ImageView thumb;
    private TextView tvImgInfo, tvStatus, tvKeyInfo;
    private ProgressBar progress;
    private LinearLayout resultContainer;
    private EditText etPrompt, etModel;
    private Button btnRun, btnPick, btnLog;

    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppLog.init(new java.io.File(getExternalFilesDir(null), "logs/app.log"));
        AppLog.i("Main", "QwenBboxDraw 启动，默认模型=" + ProviderConfig.DEFAULT_MODEL);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        // —————— 标题 ——————
        TextView title = new TextView(this);
        title.setText("Qwen画框 · bbox_2d 物体定位");
        title.setTextSize(21);
        title.setTextColor(0xFF7A2E8C);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("选一张图，发给 qwen3.8-flash（百炼），让它输出每个物体的 bbox_2d 坐标，"
                + "再按官方示例 ÷1000 画带颜色标签的框。逻辑移植自 qwen3-vl-2d.py。");
        intro.setTextSize(12.5f);
        intro.setTextColor(0xFF666666);
        intro.setPadding(0, dp(6), 0, dp(14));
        root.addView(intro);

        // —————— ① 选图 ——————
        TextView l1 = new TextView(this);
        l1.setText("① 选择图片（原图直传）");
        l1.setTextSize(15);
        l1.setTextColor(0xFF7A2E8C);
        l1.setTypeface(null, Typeface.BOLD);
        root.addView(l1);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        btnPick = new Button(this);
        btnPick.setText("选择图片");
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        tvImgInfo = new TextView(this);
        tvImgInfo.setText("未选择图片");
        tvImgInfo.setTextSize(12);
        tvImgInfo.setTextColor(0xFF888888);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pp.leftMargin = dp(10);
        pp.gravity = Gravity.CENTER_VERTICAL;
        tvImgInfo.setLayoutParams(pp);
        pickRow.addView(btnPick);
        pickRow.addView(tvImgInfo);
        root.addView(pickRow);

        thumb = new ImageView(this);
        thumb.setAdjustViewBounds(true);
        thumb.setMaxHeight(dp(220));
        thumb.setVisibility(View.GONE);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp.topMargin = dp(6);
        thumb.setLayoutParams(thLp);
        root.addView(thumb);

        // —————— ② 提示词 + 配置 ——————
        TextView l2 = new TextView(this);
        l2.setText("② 提示词（可改）");
        l2.setTextSize(15);
        l2.setTextColor(0xFF7A2E8C);
        l2.setTypeface(null, Typeface.BOLD);
        l2.setPadding(0, dp(14), 0, dp(4));
        root.addView(l2);

        etPrompt = new EditText(this);
        etPrompt.setMinLines(2);
        etPrompt.setMaxLines(4);
        etPrompt.setGravity(Gravity.TOP);
        etPrompt.setTextSize(12);
        etPrompt.setText(SettingsStore.prompt(this));
        root.addView(etPrompt);

        // 提示词模板管理：保存 / 另存 / 复制 / 覆盖 / 删除 / 切换
        Button btnTpl = new Button(this);
        btnTpl.setText("🧩 提示词模板（保存 / 另存 / 复制 / 切换）");
        btnTpl.setTextSize(13);
        LinearLayout.LayoutParams tplLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tplLp.topMargin = dp(6);
        btnTpl.setLayoutParams(tplLp);
        btnTpl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTemplateDialog(); }
        });
        root.addView(btnTpl);

        // 当前配置一览（model / key 身份）
        tvKeyInfo = new TextView(this);
        tvKeyInfo.setTextSize(11);
        tvKeyInfo.setTextColor(0xFF888888);
        tvKeyInfo.setPadding(0, dp(6), 0, dp(6));
        root.addView(tvKeyInfo);

        // 高级配置行：模型名（默认 qwen3.8-flash，可改 / 也可从列表获取）
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        etModel = new EditText(this);
        etModel.setSingleLine(true);
        etModel.setHint("模型名（默认 " + ProviderConfig.DEFAULT_MODEL + "）");
        etModel.setText(SettingsStore.model(this));
        etModel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modelRow.addView(etModel);
        Button btnModelList = new Button(this);
        btnModelList.setText("获取模型列表");
        btnModelList.setTextSize(13);
        modelRow.addView(btnModelList);
        root.addView(modelRow);

        // 模型名一改，运行按钮文案跟着变
        etModel.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateRunButtonText(s.toString().trim());
            }
        });

        btnModelList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchAndShowModelList(); }
        });

        // 高级设置按钮：暴露 API Key / 端点 / provider / 思考 / 超时 / 重试 / 退避 等更多参数
        Button btnAdvanced = new Button(this);
        btnAdvanced.setText("⚙ 高级设置（API Key / 端点 / 思考 / 超时 / 重试…）");
        btnAdvanced.setTextSize(14);
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        adLp.topMargin = dp(6);
        btnAdvanced.setLayoutParams(adLp);
        btnAdvanced.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAdvancedSettingsDialog(); }
        });
        root.addView(btnAdvanced);

        // —————— ③ 运行 ——————
        btnRun = new Button(this);
        btnRun.setTextSize(16);
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runDetect(etModel.getText().toString().trim()); }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(10);
        btnRun.setLayoutParams(rl);
        root.addView(btnRun);
        updateRunButtonText(etModel.getText().toString().trim());

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(0xFF7A2E8C);
        tvStatus.setPadding(0, dp(6), 0, dp(6));
        root.addView(tvStatus);

        // —————— 结果 ——————
        resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultContainer);

        // —————— 底部 日志 ——————
        btnLog = new Button(this);
        btnLog.setText("查看运行日志");
        btnLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogViewActivity.class));
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(18);
        btnLog.setLayoutParams(bl);
        root.addView(btnLog);

        // —————— 底部 作者信息 ——————
        TextView about = new TextView(this);
        about.setText("作者：奇思妙语胡言乱语　UID 1525999910\n主页 space.bilibili.com/1525999910（点按打开）");
        about.setTextSize(11);
        about.setTextColor(0xFF888888);
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        about.setPadding(0, dp(12), 0, dp(4));
        about.setClickable(true);
        about.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/1525999910")));
                } catch (Exception e) {
                    toast("无法打开浏览器：" + e.getMessage());
                }
            }
        });
        root.addView(about);

        refreshKeyInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshKeyInfo();
        etPrompt.setText(SettingsStore.prompt(this));
        updateRunButtonText(etModel.getText().toString().trim());
    }

    private void refreshKeyInfo() {
        String key = SettingsStore.apiKey(this);
        tvKeyInfo.setText("端点=" + SettingsStore.baseUrl(this)
                + " ｜ provider=" + SettingsStore.provider(this)
                + " ｜ 思考=" + (SettingsStore.enableThinking(this) ? "开" : "关")
                + " ｜ key=" + VisionApiClient.keyIdentity(key));
    }

    private void updateRunButtonText(String model) {
        String m = (model == null || model.isEmpty()) ? ProviderConfig.DEFAULT_MODEL : model;
        btnRun.setText("③ 开始检测（发给 " + m + "）");
    }

    // —————— 模型列表：从当前供应商拉取，供下拉选择（仍支持手动输入）——————

    private void fetchAndShowModelList() {
        final String baseUrl = SettingsStore.baseUrl(this);
        final String apiKey = SettingsStore.apiKey(this);
        final int timeoutMs = Math.max(15_000, SettingsStore.timeoutMs(this));
        toast("正在获取模型列表…");
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<String> models;
                String err;
                try {
                    models = VisionApiClient.fetchModelList(apiKey, baseUrl, timeoutMs);
                    err = null;
                } catch (Exception e) {
                    models = null;
                    err = (e.getMessage() == null) ? e.getClass().getSimpleName() : e.getMessage();
                    AppLog.e("Main", "获取模型列表失败", e);
                }
                final java.util.List<String> fmodels = models;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ferr != null) { toast("获取模型列表失败：" + ferr); return; }
                        if (fmodels == null || fmodels.isEmpty()) { toast("未获取到模型（或端点不支持 /models）"); return; }
                        showModelListDialog(fmodels);
                    }
                });
            }
        }).start();
    }

    private void showModelListDialog(java.util.List<String> models) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        d.setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("选择模型（共 " + models.size() + " 个）");
        title.setTextSize(18);
        title.setTextColor(0xFF7A2E8C);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(8));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("点选即可填入模型名；也可关闭后直接在输入框手动输入。");
        hint.setTextSize(11);
        hint.setTextColor(0xFF888888);
        hint.setPadding(0, 0, 0, dp(8));
        root.addView(hint);

        for (int i = 0; i < models.size(); i++) {
            final String mid = models.get(i);
            TextView row = new TextView(this);
            row.setText((i + 1) + ".  " + mid);
            row.setTextSize(14);
            row.setTextColor(0xFF333333);
            row.setPadding(dp(6), dp(8), dp(6), dp(8));
            row.setBackgroundColor((i % 2 == 0) ? 0xFFF5F5F5 : 0xFFFFFFFF);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    etModel.setText(mid);
                    d.dismiss();
                    toast("已选用模型：" + mid);
                }
            });
            root.addView(row);
        }

        Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(14);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(16);
        close.setLayoutParams(cl);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        root.addView(close);

        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    // —————— 高级设置对话框：把代码里写死的参数全部暴露给用户 ——————

    private void showAdvancedSettingsDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        d.setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("高级设置");
        title.setTextSize(18);
        title.setTextColor(0xFF7A2E8C);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(10));
        root.addView(title);

        // 供应商：下拉选择（端点 / API Key 交由「管理供应商」面板维护，这里不再重复展示）
        final java.util.List<ProviderStore.Provider>[] provHolder = new java.util.List[1];
        provHolder[0] = ProviderStore.list(this);
        final ProviderStore.Provider[] selProvider = new ProviderStore.Provider[1];
        final Spinner spProvider = new Spinner(this);
        final Runnable reloadProviders = new Runnable() {
            @Override public void run() {
                provHolder[0] = ProviderStore.list(MainActivity.this);
                String[] ls = new String[provHolder[0].size()];
                for (int i = 0; i < provHolder[0].size(); i++)
                    ls[i] = provHolder[0].get(i).name + "（" + provHolder[0].get(i).type + "）";
                spProvider.setAdapter(new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, ls));
            }
        };

        root.addView(settingLabel("供应商（下拉选择）"));
        String[] labels = new String[provHolder[0].size()];
        for (int i = 0; i < provHolder[0].size(); i++) {
            ProviderStore.Provider p = provHolder[0].get(i);
            labels[i] = p.name + "（" + p.type + "）";
        }
        spProvider.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                selProvider[0] = provHolder[0].get(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(spProvider);

        // 管理供应商（添加/编辑/删除）
        Button btnManage = new Button(this);
        btnManage.setText("⚙ 管理供应商（添加 / 编辑 / 删除）");
        btnManage.setTextSize(13);
        LinearLayout.LayoutParams mg = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mg.topMargin = dp(6);
        btnManage.setLayoutParams(mg);
        btnManage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showManageProvidersDialog(reloadProviders); }
        });
        root.addView(btnManage);

        // 预选当前保存的供应商（按 id 或 type 匹配）
        String curProvider = SettingsStore.provider(this);
        String curUrl = SettingsStore.baseUrl(this);
        int sel = -1;
        for (int i = 0; i < provHolder[0].size(); i++) {
            ProviderStore.Provider p = provHolder[0].get(i);
            if (p.id.equals(curProvider) || p.type.equals(curProvider)
                    || p.baseUrl.equals(curUrl)) { sel = i; break; }
        }
        if (sel >= 0 && sel < provHolder[0].size()) spProvider.setSelection(sel);

        // 思考开关
        final CheckBox cbThink = new CheckBox(this);
        cbThink.setText("启用思考（深度推理）；关闭时按 provider 写关思考字段");
        cbThink.setTextSize(14);
        cbThink.setChecked(SettingsStore.enableThinking(this));
        root.addView(cbThink);

        // 超时（秒）
        root.addView(settingLabel("请求超时（秒）"));
        final EditText etTimeout = new EditText(this);
        etTimeout.setSingleLine(true);
        etTimeout.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etTimeout.setText(String.valueOf(SettingsStore.timeoutMs(this) / 1000));
        root.addView(etTimeout);

        // 重试次数
        root.addView(settingLabel("重试次数（首次失败后再重试；0=不重试）"));
        final EditText etRetries = new EditText(this);
        etRetries.setSingleLine(true);
        etRetries.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etRetries.setText(String.valueOf(SettingsStore.maxRetries(this)));
        root.addView(etRetries);

        // 退避基准（ms）
        root.addView(settingLabel("退避基准（毫秒，指数退避 base*2^k）"));
        final EditText etBackoff = new EditText(this);
        etBackoff.setSingleLine(true);
        etBackoff.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etBackoff.setText(String.valueOf(SettingsStore.backoffMs(this)));
        root.addView(etBackoff);

        // 保存 / 取消
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 端点 / API Key 由所选供应商提供（供应商面板统一维护）
                if (selProvider[0] != null) {
                    SettingsStore.saveProvider(MainActivity.this, selProvider[0].type);
                    SettingsStore.saveBaseUrl(MainActivity.this, selProvider[0].baseUrl);
                    SettingsStore.saveApiKey(MainActivity.this, selProvider[0].apiKey);
                }
                SettingsStore.saveEnableThinking(MainActivity.this, cbThink.isChecked());
                int timeoutSec = parseInt(etTimeout.getText().toString());
                if (timeoutSec > 0) SettingsStore.saveTimeoutMs(MainActivity.this, timeoutSec * 1000);
                SettingsStore.saveMaxRetries(MainActivity.this, parseInt(etRetries.getText().toString()));
                long backoff = parseLong(etBackoff.getText().toString());
                if (backoff >= 0) SettingsStore.saveBackoffMs(MainActivity.this, backoff);
                refreshKeyInfo();
                d.dismiss();
                toast("高级设置已保存");
            }
        });
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cb.leftMargin = dp(8);
        save.setLayoutParams(sb);
        cancel.setLayoutParams(cb);
        btnRow.addView(save);
        btnRow.addView(cancel);
        root.addView(btnRow);

        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    // —————— 供应商管理面板：添加 / 编辑 / 删除 ——————

    private void showManageProvidersDialog(final Runnable reloadAdvanced) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        d.setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("管理供应商");
        title.setTextSize(18);
        title.setTextColor(0xFF7A2E8C);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(8));
        root.addView(title);

        // 添加供应商
        Button btnAdd = new Button(this);
        btnAdd.setText("➕ 添加供应商");
        btnAdd.setTextSize(14);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        a.topMargin = dp(6);
        btnAdd.setLayoutParams(a);
        root.addView(btnAdd);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(12), 0, 0);
        root.addView(listContainer);

        Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(14);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(16);
        close.setLayoutParams(cl);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reloadAdvanced.run(); d.dismiss(); }
        });
        root.addView(close);

        final Runnable render = buildProviderListRenderer(listContainer, reloadAdvanced);
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showProviderEditDialog(null, false, new Runnable() {
                    @Override public void run() { render.run(); }
                });
            }
        });

        render.run();
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    private Runnable buildProviderListRenderer(final LinearLayout container,
                                               final Runnable reloadAdvanced) {
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            @Override public void run() {
                container.removeAllViews();
                final java.util.List<ProviderStore.Provider> all = ProviderStore.list(MainActivity.this);
                if (all.isEmpty()) {
                    container.addView(makeText("还没有供应商。点「添加供应商」新建一个。",
                            12, 0xFF888888, 0));
                    return;
                }
                TextView hdr = new TextView(MainActivity.this);
                hdr.setText("已有 " + all.size() + " 个供应商：");
                hdr.setTextSize(13);
                hdr.setTextColor(0xFF7A2E8C);
                hdr.setTypeface(null, Typeface.BOLD);
                hdr.setPadding(0, 0, 0, dp(6));
                container.addView(hdr);

                for (int i = 0; i < all.size(); i++) {
                    final ProviderStore.Provider p = all.get(i);
                    LinearLayout card = new LinearLayout(MainActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(10), dp(8), dp(10), dp(8));
                    card.setBackgroundColor(0xFFF5F5F5);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    clp.bottomMargin = dp(8);
                    card.setLayoutParams(clp);

                    TextView nameTv = new TextView(MainActivity.this);
                    nameTv.setText("#" + (i + 1) + "  " + p.name + "（" + p.type + "）");
                    nameTv.setTextSize(15);
                    nameTv.setTextColor(0xFF7A2E8C);
                    nameTv.setTypeface(null, Typeface.BOLD);
                    card.addView(nameTv);
                    TextView urlTv = new TextView(MainActivity.this);
                    urlTv.setText(p.baseUrl == null || p.baseUrl.isEmpty() ? "（未设置端点）" : p.baseUrl);
                    urlTv.setTextSize(11);
                    urlTv.setTextColor(0xFF666666);
                    urlTv.setPadding(0, dp(2), 0, dp(4));
                    card.addView(urlTv);

                    LinearLayout btnRow = new LinearLayout(MainActivity.this);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);
                    btnRow.addView(makeRowBtn("修改", new Runnable() {
                        @Override public void run() {
                            showProviderEditDialog(p, true, new Runnable() {
                                @Override public void run() { self[0].run(); }
                            });
                        }
                    }));
                    btnRow.addView(makeRowBtn("删除", new Runnable() {
                        @Override public void run() {
                            showConfirmDialog("确定删除供应商「" + p.name + "」吗？", new Runnable() {
                                @Override public void run() {
                                    ProviderStore.remove(MainActivity.this, p.id);
                                    toast("已删除供应商「" + p.name + "」");
                                    self[0].run();
                                }
                            });
                        }
                    }));
                    card.addView(btnRow);
                    container.addView(card);
                }
            }
        };
        return self[0];
    }

    /** 添加 / 编辑供应商弹窗。existing 为 null 表示新增。 */
    private void showProviderEditDialog(final ProviderStore.Provider existing,
                                        final boolean revealKey, final Runnable afterSave) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        d.setContentView(scroll);

        TextView tv = new TextView(this);
        tv.setText(existing == null ? "添加供应商" : "编辑供应商");
        tv.setTextSize(17);
        tv.setTextColor(0xFF7A2E8C);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(10));
        root.addView(tv);

        root.addView(settingLabel("名称"));
        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setText(existing == null ? "" : existing.name);
        root.addView(etName);

        root.addView(settingLabel("类型（决定关思考字段写法）"));
        final Spinner spType = new Spinner(this);
        final String[] types = {"bailian", "deepseek", "custom"};
        spType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, types));
        if (existing != null) {
            for (int i = 0; i < types.length; i++)
                if (types[i].equals(existing.type)) { spType.setSelection(i); break; }
        }
        root.addView(spType);

        root.addView(settingLabel("Base URL / 端点（不含 /chat/completions）"));
        final EditText etUrl = new EditText(this);
        etUrl.setSingleLine(true);
        etUrl.setText(existing == null ? "" : existing.baseUrl);
        root.addView(etUrl);

        final EditText etKey = addSecretKeyRow(root, "API Key", "sk-…",
                existing == null ? "" : existing.apiKey, revealKey);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) { toast("请填写供应商名称"); return; }
                final String id;
                if (existing != null) {
                    id = existing.id;
                } else {
                    String base = name.toLowerCase().trim();
                    id = uniqueId(MainActivity.this, base);
                }
                String type = types[spType.getSelectedItemPosition()];
                String url = etUrl.getText().toString().trim();
                String key = etKey.getText().toString().trim();
                boolean isNew = ProviderStore.put(MainActivity.this,
                        new ProviderStore.Provider(id, name, type, url, key));
                toast(isNew ? "已添加供应商「" + name + "」" : "已更新供应商「" + name + "」");
                d.dismiss();
                afterSave.run();
            }
        });
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w2.leftMargin = dp(8);
        save.setLayoutParams(w);
        cancel.setLayoutParams(w2);
        btnRow.addView(save);
        btnRow.addView(cancel);
        root.addView(btnRow);
        d.setCancelable(true);
        d.show();
    }

    private static String uniqueId(Context c, String base) {
        String id = base;
        int n = 2;
        while (ProviderStore.hasId(c, id)) { id = base + "_" + n; n++; }
        return id;
    }

    private TextView settingLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFF7A2E8C);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    /**
     * 密钥输入行：默认密码式遮挡（●●●），右侧「显示/隐藏」按钮可临时查看明文。
     * 返回该 EditText，供保存时取值。
     */
    private EditText addSecretKeyRow(LinearLayout root, String label, String hint,
                                     String initial, boolean reveal) {
        root.addView(settingLabel(label));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint(hint);
        et.setText(initial == null ? "" : initial);
        et.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        et.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(et);
        final Button tg = new Button(this);
        tg.setTextSize(12);
        if (reveal) {
            et.setTransformationMethod(null);
            tg.setText("隐藏");
        } else {
            tg.setText("显示");
        }
        tg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean masked = et.getTransformationMethod()
                        instanceof android.text.method.PasswordTransformationMethod;
                if (masked) {
                    et.setTransformationMethod(null);
                    tg.setText("隐藏");
                } else {
                    et.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                    tg.setText("显示");
                }
                et.setSelection(et.length());
            }
        });
        row.addView(tg);
        root.addView(row);
        return et;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1L; }
    }

    // —————— 提示词模板：保存 / 复制 / 重命名 / 编辑 / 删除 / 切换 ——————

    /** 回调：文本对话框确认后返回输入值。 */
    private interface TextCallback { void onOk(String value); }

    private void showTemplateDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        d.setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("提示词模板");
        title.setTextSize(18);
        title.setTextColor(0xFF7A2E8C);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(8));
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("点击模板名称=载入到提示词框；「编辑」可修改该模板内容（不自动覆盖当前提示词框）。");
        tip.setTextSize(11);
        tip.setTextColor(0xFF888888);
        tip.setPadding(0, 0, 0, dp(8));
        root.addView(tip);

        // 模板列表容器
        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(12), 0, 0);
        root.addView(listContainer);

        final Runnable render = buildTemplateListRenderer(listContainer, d);

        // 保存当前提示词为新模板（弹窗输入名字）
        Button btnSaveNew = new Button(this);
        btnSaveNew.setText("💾 保存当前提示词为新模板");
        btnSaveNew.setTextSize(14);
        LinearLayout.LayoutParams sn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sn.topMargin = dp(10);
        btnSaveNew.setLayoutParams(sn);
        root.addView(btnSaveNew);

        // 添加新的模板：直接输入名字 + 内容，新建一条空/自定义模板
        Button btnAdd = new Button(this);
        btnAdd.setText("➕ 添加新的模板");
        btnAdd.setTextSize(14);
        LinearLayout.LayoutParams ad = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ad.topMargin = dp(6);
        btnAdd.setLayoutParams(ad);
        root.addView(btnAdd);

        // 关闭
        Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(14);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(16);
        close.setLayoutParams(cl);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        root.addView(close);

        btnSaveNew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String content = etPrompt.getText().toString().trim();
                if (content.isEmpty()) { toast("提示词框为空，无可保存内容"); return; }
                showNameInputDialog("保存当前提示词为新模板", "", new TextCallback() {
                    @Override public void onOk(String name) {
                        boolean isNew = TemplateStore.put(MainActivity.this, name, content);
                        toast(isNew ? "已保存模板「" + name + "」" : "已覆盖同名模板「" + name + "」");
                        render.run();
                    }
                });
            }
        });

        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showAddTemplateDialog(render);
            }
        });

        render.run();
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    /** 「添加新的模板」：输入名字 + 内容，直接新建一条模板。 */
    private void showAddTemplateDialog(final Runnable refresh) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        d.setContentView(scroll);

        TextView tv = new TextView(this);
        tv.setText("添加新的模板");
        tv.setTextSize(17);
        tv.setTextColor(0xFF7A2E8C);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(10));
        root.addView(tv);

        root.addView(settingLabel("模板名称"));
        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setHint("例如：嘴角定位 / 黑丝 / 人脸 …");
        root.addView(etName);

        root.addView(settingLabel("模板内容（提示词）"));
        final EditText etContent = new EditText(this);
        etContent.setMinLines(4);
        etContent.setMaxLines(10);
        etContent.setGravity(Gravity.TOP);
        etContent.setHint("识别图片中的…，并以JSON格式输出其bbox的坐标及其中文名称");
        root.addView(etContent);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) { toast("请填写模板名称"); return; }
                String content = etContent.getText().toString().trim();
                if (content.isEmpty()) { toast("请填写模板内容"); return; }
                boolean isNew = TemplateStore.put(MainActivity.this, name, content);
                toast(isNew ? "已新增模板「" + name + "」" : "已覆盖同名模板「" + name + "」");
                d.dismiss();
                refresh.run();
            }
        });
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w2.leftMargin = dp(8);
        save.setLayoutParams(w);
        cancel.setLayoutParams(w2);
        btnRow.addView(save);
        btnRow.addView(cancel);
        root.addView(btnRow);

        d.setCancelable(true);
        d.show();
    }

    /** 渲染模板列表；每次变更后重跑，清空重建底部行。 */
    private Runnable buildTemplateListRenderer(final LinearLayout container, final Dialog d) {
        return new Runnable() {
            @Override public void run() {
                container.removeAllViews();
                java.util.List<TemplateStore.Template> all = TemplateStore.list(MainActivity.this);
                if (all.isEmpty()) {
                    container.addView(makeText("还没有模板。点「保存当前提示词为新模板」新建一个。",
                            12, 0xFF888888, 0));
                    return;
                }
                TextView hdr = new TextView(MainActivity.this);
                hdr.setText("已有 " + all.size() + " 个模板：");
                hdr.setTextSize(13);
                hdr.setTextColor(0xFF7A2E8C);
                hdr.setTypeface(null, Typeface.BOLD);
                hdr.setPadding(0, 0, 0, dp(6));
                container.addView(hdr);

                for (int i = 0; i < all.size(); i++) {
                    final TemplateStore.Template t = all.get(i);
                    LinearLayout card = new LinearLayout(MainActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(10), dp(8), dp(10), dp(8));
                    card.setBackgroundColor(0xFFF5F5F5);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    clp.bottomMargin = dp(8);
                    card.setLayoutParams(clp);

                    // 名称可点，载入到提示词框
                    TextView nameTv = new TextView(MainActivity.this);
                    nameTv.setText("#" + (i + 1) + "  " + t.name);
                    nameTv.setTextSize(15);
                    nameTv.setTextColor(0xFF7A2E8C);
                    nameTv.setTypeface(null, Typeface.BOLD);
                    nameTv.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            etPrompt.setText(t.content);
                            d.dismiss();
                            toast("已载入模板「" + t.name + "」到提示词框");
                        }
                    });
                    card.addView(nameTv);

                    // 内容预览（单行省略）
                    TextView preview = new TextView(MainActivity.this);
                    String one = t.content.replace('\n', ' ').trim();
                    preview.setText(one.length() > 60 ? one.substring(0, 60) + "…" : one);
                    preview.setTextSize(11);
                    preview.setTextColor(0xFF666666);
                    preview.setPadding(0, dp(2), 0, dp(4));
                    card.addView(preview);

                    // 操作按钮行：复制 / 重命名 / 编辑 / 删除
                    LinearLayout btnRow = new LinearLayout(MainActivity.this);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);
                    btnRow.addView(makeRowBtn("复制", new Runnable() {
                        @Override public void run() {
                            String nn = TemplateStore.duplicate(MainActivity.this, t.name);
                            toast(nn == null ? "复制失败" : "已复制为「" + nn + "」");
                            renderList();
                        }
                    }));
                    btnRow.addView(makeRowBtn("重命名", new Runnable() {
                        @Override public void run() {
                            showNameInputDialog("重命名模板", t.name, new TextCallback() {
                                @Override public void onOk(String name) {
                                    boolean ok = TemplateStore.rename(MainActivity.this, t.name, name);
                                    toast(ok ? "已重命名为「" + name + "」"
                                            : "重命名失败（名字为空/未变/已被占用）");
                                    renderList();
                                }
                            });
                        }
                    }));
                    btnRow.addView(makeRowBtn("编辑", new Runnable() {
                        @Override public void run() {
                            showEditContentDialog(t.name, t.content, new TextCallback() {
                                @Override public void onOk(String newContent) {
                                    TemplateStore.overwrite(MainActivity.this, t.name, newContent);
                                    toast("已更新模板「" + t.name + "」内容");
                                    renderList();
                                }
                            });
                        }
                    }));
                    btnRow.addView(makeRowBtn("删除", new Runnable() {
                        @Override public void run() {
                            showConfirmDialog("确定删除模板「" + t.name + "」吗？", new Runnable() {
                                @Override public void run() {
                                    TemplateStore.remove(MainActivity.this, t.name);
                                    toast("已删除「" + t.name + "」");
                                    renderList();
                                }
                            });
                        }
                    }));
                    card.addView(btnRow);

                    container.addView(card);
                }
            }

            // 局部重绘：清除容器并重建（供按钮监听复用）
            void renderList() { run(); }
        };
    }

    private Button makeRowBtn(String label, final Runnable action) {
        Button b = new Button(MainActivity.this);
        b.setText(label);
        b.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    /** 弹窗让你输入文本（重命名 / 新建模板名共用），返回空串回调方另行处理。 */
    private void showNameInputDialog(String title, String initial, final TextCallback cb) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        d.setContentView(root);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(17);
        tv.setTextColor(0xFF7A2E8C);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(10));
        root.addView(tv);

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial == null ? "" : initial);
        et.setSelectAllOnFocus(true);
        root.addView(et);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        Button ok = new Button(this);
        ok.setText("确定");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String val = et.getText().toString().trim();
                if (val.isEmpty()) { toast("名字不能为空"); return; }
                d.dismiss();
                cb.onOk(val);
            }
        });
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w2.leftMargin = dp(8);
        ok.setLayoutParams(w);
        cancel.setLayoutParams(w2);
        btnRow.addView(ok);
        btnRow.addView(cancel);
        root.addView(btnRow);

        d.setCancelable(true);
        d.show();
    }

    /** 编辑模板内容：多行文本框，保存回写。 */
    private void showEditContentDialog(String name, String content, final TextCallback cb) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        d.setContentView(root);

        TextView tv = new TextView(this);
        tv.setText("编辑模板内容 · " + name);
        tv.setTextSize(17);
        tv.setTextColor(0xFF7A2E8C);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(10));
        root.addView(tv);

        final EditText et = new EditText(this);
        et.setMinLines(4);
        et.setMaxLines(10);
        et.setGravity(Gravity.TOP);
        et.setText(content == null ? "" : content);
        root.addView(et);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String val = et.getText().toString().trim();
                if (val.isEmpty()) { toast("内容不能为空"); return; }
                d.dismiss();
                cb.onOk(val);
            }
        });
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w2.leftMargin = dp(8);
        save.setLayoutParams(w);
        cancel.setLayoutParams(w2);
        btnRow.addView(save);
        btnRow.addView(cancel);
        root.addView(btnRow);

        d.setCancelable(true);
        d.show();
    }

    /** 确认弹窗。 */
    private void showConfirmDialog(String msg, final Runnable onYes) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        d.setContentView(root);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(15);
        tv.setTextColor(0xFF333333);
        tv.setPadding(0, dp(4), 0, dp(6));
        root.addView(tv);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        Button yes = new Button(this);
        yes.setText("确定");
        yes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); onYes.run(); }
        });
        Button no = new Button(this);
        no.setText("取消");
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w2.leftMargin = dp(8);
        yes.setLayoutParams(w);
        no.setLayoutParams(w2);
        btnRow.addView(yes);
        btnRow.addView(no);
        root.addView(btnRow);

        d.setCancelable(true);
        d.show();
    }

    // —————— 选图 ——————

    private void pickImage() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+：优先打开系统相册（Photo Picker），无需存储权限
            try {
                startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES), REQ_PICK);
                return;
            } catch (Exception e) {
                AppLog.w("Main", "相册选择器不可用，回退文件浏览器：" + e.getMessage());
            }
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("没有可用的选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            loadPickedImage(data.getData());
        }
    }

    private void loadPickedImage(Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = loadFromUri(uri, opts);
            if (bmp == null) { toast("无法解码图片"); return; }
            origBmp = bmp;
            imgW = bmp.getWidth();
            imgH = bmp.getHeight();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, bos);
            jpegBytes = bos.toByteArray();
            tvImgInfo.setText(imgW + " × " + imgH + "px · JPEG " + (jpegBytes.length / 1024) + "KB");
            thumb.setImageBitmap(bmp);
            thumb.setVisibility(View.VISIBLE);
            AppLog.i("Main", "已选图：" + imgW + "x" + imgH + "，JPEG " + jpegBytes.length + "字节（原图直传）");
        } catch (Exception e) {
            AppLog.e("Main", "选图失败", e);
            toast("选图失败：" + e.getMessage());
        }
    }

    private Bitmap loadFromUri(Uri uri, BitmapFactory.Options opts) {
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is != null) return BitmapFactory.decodeStream(is, null, opts);
        } catch (Exception e) {
            AppLog.w("Main", "openInputStream 失败，查路径：" + e.getMessage());
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignore) {}
        }
        String path = queryFilePath(uri);
        return path != null ? BitmapFactory.decodeFile(path, opts) : null;
    }

    private String queryFilePath(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) return uri.getPath();
        Cursor c = null;
        try {
            String[] col = { MediaStore.Images.Media.DATA };
            c = getContentResolver().query(uri, col, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(c.getColumnIndexOrThrow(col[0]));
        } catch (Exception e) {
            AppLog.w("Main", "查询文件路径失败：" + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // —————— 检测 ——————

    private void runDetect(String modelInput) {
        if (running) { toast("正在检测中…"); return; }
        if (jpegBytes == null || origBmp == null) { toast("请先选择图片"); return; }
        final String prompt = etPrompt.getText().toString().trim();
        if (prompt.isEmpty()) { toast("提示词不能为空"); return; }

        final String model = modelInput.isEmpty() ? ProviderConfig.DEFAULT_MODEL : modelInput;
        SettingsStore.saveModel(this, model);
        SettingsStore.savePrompt(this, prompt);

        final String baseUrl = SettingsStore.baseUrl(this);
        final String apiKey = SettingsStore.apiKey(this);
        final String provider = SettingsStore.provider(this);
        final boolean think = SettingsStore.enableThinking(this);
        final int timeoutMs = SettingsStore.timeoutMs(this);
        final int maxRetries = SettingsStore.maxRetries(this);
        final long backoffMs = SettingsStore.backoffMs(this);

        running = true;
        btnRun.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        resultContainer.removeAllViews();
        tvStatus.setText("请求中… model=" + model + " provider=" + provider
                + " think=" + think + " 超时=" + timeoutMs + "ms"
                + " key=" + VisionApiClient.keyIdentity(apiKey));
        AppLog.i("Main", "开始检测：model=" + model + "，provider=" + provider
                + "，think=" + think + "，超时=" + timeoutMs + "ms，重试=" + maxRetries
                + "，退避=" + backoffMs + "ms，图=" + imgW + "×" + imgH
                + "，prompt=" + prompt.length() + "字符");

        final String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
        new Thread(new Runnable() {
            @Override public void run() {
                String raw = null, err = null;
                try {
                    VisionApiClient client = new VisionApiClient(apiKey, baseUrl, model,
                            maxRetries, backoffMs, provider, think);
                    raw = client.chatWithImage("", prompt, dataUrl, timeoutMs);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    AppLog.e("Main", "检测失败：" + err, e);
                }
                final String fraw = raw, ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        running = false;
                        btnRun.setEnabled(true);
                        progress.setVisibility(View.GONE);
                        if (ferr != null) {
                            tvStatus.setText("❌ 失败：" + ferr);
                            resultContainer.addView(makeText("请求失败：" + ferr
                                    + "\n\n可到「查看运行日志」看完整请求/返回。", 13, 0xFFB71C1C, dp(8)));
                        } else {
                            tvStatus.setText("✅ 完成（model=" + model + "）");
                            renderResults(fraw == null ? "" : fraw);
                        }
                    }
                });
            }
        }).start();
    }

    // —————— 渲染：忠实移植 plot_bounding_boxes ——————

    private void renderResults(String raw) {
        // 顶部：模型返回原文
        LinearLayout rawCard = new LinearLayout(this);
        rawCard.setOrientation(LinearLayout.VERTICAL);
        rawCard.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams rc = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rc.topMargin = dp(6);
        rc.bottomMargin = dp(10);
        rawCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        rawCard.setLayoutParams(rc);

        rawCard.addView(makeLabel("模型返回原文（点击展开全屏查看）", 14, 0xFF7A2E8C));
        TextView rawText = new TextView(this);
        rawText.setText(raw);
        rawText.setTextSize(11);
        rawText.setTypeface(Typeface.MONOSPACE);
        rawText.setTextColor(0xFF333333);
        rawText.setMaxLines(40);
        rawText.setMovementMethod(ScrollingMovementMethod.getInstance());
        rawText.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams rt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        rt.topMargin = dp(6);
        rawText.setLayoutParams(rt);
        final String fraw = raw;
        rawText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTextViewFullscreen("模型返回原文", fraw); }
        });
        rawCard.addView(rawText);
        resultContainer.addView(rawCard);

        // 解析 bbox_2d 物体
        List<BboxParser.Item> items = BboxParser.parse(raw);
        if (items.isEmpty()) {
            resultContainer.addView(makeText(
                    "未能从返回中解析出 bbox_2d 物体。\n"
                    + "本 App 期望模型返回类似 [{\"label\":\"物体名\",\"bbox_2d\":[x1,y1,x2,y2]}, ...] 的 JSON。\n"
                    + "可在上方原文查看模型实际返回了什么。",
                    13, 0xFFB00020, dp(8)));
            return;
        }

        TextView count = new TextView(this);
        count.setText("共解析出 " + items.size() + " 个物体，按 bbox_2d ÷1000 画框：");
        count.setTextSize(13);
        count.setTextColor(0xFF7A2E8C);
        resultContainer.addView(count);

        // 打框图（移植 plot_bounding_boxes）
        Bitmap marked = drawBoxes(origBmp, items);
        if (marked != null) {
            final Bitmap shown = marked;
            TextView picLabel = makeLabel("打框结果（点图放大）", 14, 0xFF2E7D32);
            picLabel.setPadding(0, dp(8), 0, dp(4));
            resultContainer.addView(picLabel);
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(shown);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showZoomViewer(shown, "打框结果"); }
            });
            resultContainer.addView(iv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 保存打框图到相册
            Button btnSave = new Button(this);
            btnSave.setText("💾 保存打框图到相册");
            btnSave.setTextSize(14);
            LinearLayout.LayoutParams sv = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sv.topMargin = dp(8);
            btnSave.setLayoutParams(sv);
            btnSave.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { saveToGallery(shown); }
            });
            resultContainer.addView(btnSave);
        }

        // 明细列表
        TextView detailLabel = makeLabel("明细", 14, 0xFF7A2E8C);
        detailLabel.setPadding(0, dp(10), 0, dp(4));
        resultContainer.addView(detailLabel);
        for (int i = 0; i < items.size(); i++) {
            BboxParser.Item it = items.get(i);
            if (it.bbox2d == null || it.bbox2d.length < 4) continue;
            String line = "#" + (i + 1) + " " + (it.label.isEmpty() ? "(无标签)" : it.label)
                    + "  bbox_2d=[" + fmt(it.bbox2d[0]) + "," + fmt(it.bbox2d[1]) + ","
                    + fmt(it.bbox2d[2]) + "," + fmt(it.bbox2d[3]) + "]";
            resultContainer.addView(makeText(line, 12, 0xFF444444, dp(2)));
        }
    }

    /**
     * 在原图上画所有物体的框 —— 忠实移植 qwen3-vl-2d.py 的 plot_bounding_boxes：
     * 每个物体配一个颜色；{@code abs_x1 = bbox_2d[0]/1000*width}，
     * {@code abs_y1 = bbox_2d[1]/1000*height}，{@code abs_x2 = bbox_2d[2]/1000*width}，
     * {@code abs_y2 = bbox_2d[3]/1000*height}；若 x1>x2 / y1>y2 互换；画矩形 + 标签文字。
     */
    private Bitmap drawBoxes(Bitmap src, List<BboxParser.Item> items) {
        if (src == null || items == null || items.isEmpty()) return null;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        int w = out.getWidth(), h = out.getHeight();

        // 颜色表（移植 Python 的 colors 列表，取若干常用色循环）
        int[] colors = {
                0xFFFF0000, 0xFF008000, 0xFF0000FF, 0xFFFFFF00, 0xFFFFA500,
                0xFFFFC0CB, 0xFF800080, 0xFF8B4513, 0xFF808080, 0xFF00CED1,
                0xFF00FFFF, 0xFFFF00FF, 0xFF32CD32, 0xFF000080, 0xFF800000,
                0xFF008080, 0xFF808000, 0xFFFF7F50, 0xFFE6E6FA, 0xFF9400D3,
                0xFFEE82EE, 0xFFFFD700, 0xFFC0C0C0,
        };

        // 标签文字画笔（Android 默认字体含 CJK 回退，能渲染中文）
        float labelSize = Math.max(16f, w / 60f);
        float strokeW = Math.max(3f, w / 250f);
        Random rnd = new Random(0);

        for (int i = 0; i < items.size(); i++) {
            BboxParser.Item it = items.get(i);
            if (it.bbox2d == null || it.bbox2d.length < 4) continue;
            int color = colors[i % colors.length];

            // ÷1000 映射到绝对像素（与 Python 完全一致）
            float absX1 = it.bbox2d[0] / 1000f * w;
            float absY1 = it.bbox2d[1] / 1000f * h;
            float absX2 = it.bbox2d[2] / 1000f * w;
            float absY2 = it.bbox2d[3] / 1000f * h;
            // 互换保证 x1<x2, y1<y2
            float t;
            if (absX1 > absX2) { t = absX1; absX1 = absX2; absX2 = t; }
            if (absY1 > absY2) { t = absY1; absY1 = absY2; absY2 = t; }

            // 矩形描边
            Paint stroke = new Paint();
            stroke.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeW);
            RectF rect = new RectF(absX1, absY1, absX2, absY2);
            c.drawRect(rect, stroke);

            // 标签文字（带底色块便于辨识）
            String label = (it.label == null || it.label.isEmpty())
                    ? ("obj" + (i + 1)) : it.label;
            Paint txt = new Paint();
            txt.setColor(color);
            txt.setTextSize(labelSize);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            txt.setAntiAlias(true);
            float tw = txt.measureText(label);
            float th = labelSize;
            // 底色块（半透明黑）让文字在浅背景上也可见
            Paint bg = new Paint();
            bg.setColor(0xAA000000);
            bg.setStyle(Paint.Style.FILL);
            float bx = absX1 + 6, by = absY1 + 4;
            c.drawRect(bx, by, bx + tw + 10, by + th + 6, bg);
            c.drawText(label, bx + 5, by + th + 2, txt);
        }
        return out;
    }

    /** 把打框结果图写到系统相册（Android 10+ 用 RELATIVE_PATH，无存储权限需求）。 */
    private void saveToGallery(Bitmap bitmap) {
        if (bitmap == null) { toast("没有可保存的打框图"); return; }
        String fileName = "qwenbbox_" + System.currentTimeMillis() + ".png";
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/QwenBbox");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } else {
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
            }
            toast("已保存到相册 Pictures/QwenBbox：" + fileName);
            AppLog.i("Main", "打框图已保存到相册：" + fileName + "（uri=" + uri + "）");
        } catch (Exception e) {
            AppLog.e("Main", "保存打框图到相册失败", e);
            toast("保存失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
    /** 全屏查看大段文本（模型返回原文等）。 */
    private void showTextViewFullscreen(String title, String text) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFFFAFAFA);

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFF222222);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(52), dp(16), dp(40));
        tv.setMovementMethod(ScrollingMovementMethod.getInstance());
        sv.addView(tv);
        frame.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView bar = new TextView(this);
        bar.setText((title == null ? "" : title) + "　—　可滚动 / 长按选择复制");
        bar.setTextColor(0xFFFFFFFF);
        bar.setTextSize(14);
        bar.setPadding(dp(14), dp(12), dp(14), dp(12));
        bar.setBackgroundColor(0x99000000);
        frame.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        Button close = new Button(this);
        close.setText("✕ 关闭");
        close.setTextSize(14);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = dp(18);
        close.setLayoutParams(clp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        frame.addView(close);

        d.setContentView(frame);
        Window w = d.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    private void showZoomViewer(Bitmap bitmap, String title) {
        if (bitmap == null) return;
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF111111);

        final ZoomableImageView ziv = new ZoomableImageView(this);
        ziv.setImageBitmap(bitmap);
        ziv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(ziv);

        TextView bar = new TextView(this);
        bar.setText((title == null ? "" : title) + "　—　双指缩放 / 单指拖动 / 双击放大");
        bar.setTextColor(0xFFFFFFFF);
        bar.setTextSize(13);
        bar.setPadding(dp(14), dp(12), dp(14), dp(12));
        bar.setBackgroundColor(0x99000000);
        frame.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        Button close = new Button(this);
        close.setText("✕ 关闭");
        close.setTextSize(14);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = dp(18);
        close.setLayoutParams(clp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        frame.addView(close);

        d.setContentView(frame);
        Window w = d.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    private TextView makeLabel(String text, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }
    private TextView makeText(String text, float sp, int color, int padTop) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setPadding(dp(0), dp(padTop), dp(0), dp(0));
        return tv;
    }
    private String fmt(float v) { return String.format(java.util.Locale.US, "%.1f", v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n); }
}
