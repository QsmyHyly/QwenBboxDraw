package com.dsh.qwenbbox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 精简配置存储（QwenBboxDraw 只关心：模型、API Key、端点、提示词）。
 * 均走 SharedPreferences。默认值来自 {@link ProviderConfig}。
 */
public class SettingsStore {
    private static final String PREFS = "qwen_bbox_prefs";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "api_key";       // 用户手动填写的（覆盖内置默认）
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_PROVIDER = "provider";          // bailian / deepseek / custom
    private static final String KEY_ENABLE_THINKING = "enable_thinking"; // 是否开启思考（深度推理）
    private static final String KEY_TIMEOUT_MS = "timeout_ms";          // 单次请求超时（毫秒）
    private static final String KEY_MAX_RETRIES = "max_retries";        // 首次失败后再重试次数
    private static final String KEY_BACKOFF_MS = "backoff_ms";          // 指数退避基准（毫秒）

    /** 默认提示词（qwen3-vl-2d.py 的「识别…并以JSON格式输出其bbox的坐标及其中文名称」的通用版）。 */
    public static final String DEFAULT_PROMPT =
            "识别图片中的所有物体，并以JSON格式输出其bbox的坐标及其中文名称";

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static SharedPreferences.Editor edit(Context c) {
        return pref(c).edit();
    }

    public static String model(Context c) {
        String v = pref(c).getString(KEY_MODEL, "");
        return v.trim().isEmpty() ? ProviderConfig.DEFAULT_MODEL : v;
    }
    public static void saveModel(Context c, String v) {
        edit(c).putString(KEY_MODEL, v == null ? "" : v.trim()).apply();
    }

    public static String baseUrl(Context c) {
        String v = pref(c).getString(KEY_BASE_URL, "");
        return v.trim().isEmpty() ? ProviderConfig.BASE_URL : v;
    }
    public static void saveBaseUrl(Context c, String v) {
        edit(c).putString(KEY_BASE_URL, v == null ? "" : v.trim()).apply();
    }

    /**
     * API Key：优先用户手动填写的，否则内置默认（来自 tools/keys.local）。
     * 这里不存在 PrivacyGuard 那种「全局 key 顶替」的坑——本 App 只有百炼一家。
     */
    public static String apiKey(Context c) {
        String v = pref(c).getString(KEY_API_KEY, "");
        return v.trim().isEmpty() ? ProviderConfig.DEFAULT_API_KEY : v;
    }
    public static void saveApiKey(Context c, String v) {
        edit(c).putString(KEY_API_KEY, v == null ? "" : v.trim()).apply();
    }

    public static String prompt(Context c) {
        String v = pref(c).getString(KEY_PROMPT, "");
        return v.trim().isEmpty() ? DEFAULT_PROMPT : v;
    }
    public static void savePrompt(Context c, String v) {
        edit(c).putString(KEY_PROMPT, v == null ? "" : v).apply();
    }

    // —————— 高级参数（默认值见 ProviderConfig）——————

    /** 提供商 id：bailian（百炼，默认）/ deepseek / custom。用于决定写不写「关思考」字段。 */
    public static String provider(Context c) {
        String v = pref(c).getString(KEY_PROVIDER, "");
        return v.trim().isEmpty() ? ProviderConfig.PROVIDER_ID : v;
    }
    public static void saveProvider(Context c, String v) {
        edit(c).putString(KEY_PROVIDER, v == null ? "" : v.trim().toLowerCase()).apply();
    }

    /** 是否开启思考（深度推理）。默认关闭（对应 runDetect 里写死 false 的旧行为）。 */
    public static boolean enableThinking(Context c) {
        return pref(c).getBoolean(KEY_ENABLE_THINKING, false);
    }
    public static void saveEnableThinking(Context c, boolean v) {
        edit(c).putBoolean(KEY_ENABLE_THINKING, v).apply();
    }

    /** 单次请求超时（毫秒）。默认 90000。 */
    public static int timeoutMs(Context c) {
        int v = pref(c).getInt(KEY_TIMEOUT_MS, 0);
        return v <= 0 ? ProviderConfig.DEFAULT_TIMEOUT_MS : v;
    }
    public static void saveTimeoutMs(Context c, int v) {
        edit(c).putInt(KEY_TIMEOUT_MS, Math.max(1000, v)).apply();
    }

    /** 首次失败后再重试次数（≥0）。默认 2。 */
    public static int maxRetries(Context c) {
        int v = pref(c).getInt(KEY_MAX_RETRIES, Integer.MIN_VALUE);
        return v < 0 ? ProviderConfig.DEFAULT_MAX_RETRIES : v;
    }
    public static void saveMaxRetries(Context c, int v) {
        edit(c).putInt(KEY_MAX_RETRIES, Math.max(0, v)).apply();
    }

    /** 指数退避基准（毫秒）。默认 1000。 */
    public static long backoffMs(Context c) {
        long v = pref(c).getLong(KEY_BACKOFF_MS, -1L);
        return v < 0 ? ProviderConfig.DEFAULT_BACKOFF_MS : v;
    }
    public static void saveBackoffMs(Context c, long v) {
        edit(c).putLong(KEY_BACKOFF_MS, Math.max(0, v)).apply();
    }
}
