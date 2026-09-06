package com.dsh.qwenbbox;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商（provider）管理：把多个 API 供应商（名称/类型/端点/API Key）以 JSON 数组存在
 * SharedPreferences。设置页用下拉菜单从这些供应商里选，选中即填充端点与 Key。
 * id 唯一且作为身份；名称可显示不同。
 */
public class ProviderStore {
    private static final String PREFS = "qwen_bbox_providers";
    private static final String KEY = "providers_json";
    private static final String KEY_SEEDED = "seeded_v1";
    private static final String KEY_KEYMIGRATED = "deepseek_key_migrated_v1";

    /** DeepSeek 默认 Key：出于隐私考虑源码不写真实密钥，改为空，请在供应商面板填入。 */
    private static final String DEEPSEEK_KEY = "";

    /** 一个供应商。 */
    public static class Provider {
        public final String id;       // 稳定身份
        public final String name;     // 显示名
        public final String type;     // bailian / deepseek / custom（决定关思考字段）
        public final String baseUrl;  // 端点（不含 /chat/completions）
        public final String apiKey;   // 该供应商的 API Key（可空）

        public Provider(String id, String name, String type, String baseUrl, String apiKey) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }
    }

    /** 首次初始化预置的供应商。 */
    private static final Object[][] DEFAULT_PROVIDERS = {
            // id, name, type, baseUrl, apiKey
            {"bailian", "百炼（DashScope）", ProviderConfig.PROVIDER_ID,
                    ProviderConfig.BASE_URL, ProviderConfig.DEFAULT_API_KEY},
            {"deepseek", "DeepSeek", ProviderConfig.DEEPSEEK_ID,
                    "https://api.deepseek.com", DEEPSEEK_KEY},
    };

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 首次访问时写入默认供应商并打标记；并对已存在但缺 Key 的 DeepSeek 补一次默认 Key（幂等）。 */
    private static void seedDefaults(Context c) {
        SharedPreferences p = pref(c);
        if (!p.getBoolean(KEY_SEEDED, false)) {
            for (Object[] d : DEFAULT_PROVIDERS) {
                put(c, new Provider((String) d[0], (String) d[1], (String) d[2],
                        (String) d[3], (String) d[4]));
            }
            p.edit().putBoolean(KEY_SEEDED, true).apply();
            return;
        }
        // 迁移：老版本 DeepSeek 未配 Key，补上默认（仅当为空，不覆盖自定义）。
        if (!p.getBoolean(KEY_KEYMIGRATED, false)) {
            fillDeepseekKey(c);
            p.edit().putBoolean(KEY_KEYMIGRATED, true).apply();
        }
    }

    /** 直接读写 JSON，给 id=deepseek 且未配 Key 的供应商补默认 Key。 */
    private static void fillDeepseekKey(Context c) {
        JSONArray arr;
        try { arr = new JSONArray(pref(c).getString(KEY, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        boolean changed = false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && "deepseek".equals(o.optString("id", ""))
                    && o.optString("apiKey", "").isEmpty()) {
                try { o.put("apiKey", DEEPSEEK_KEY); } catch (Exception ignore) {}
                changed = true;
            }
            next.put(o);
        }
        if (changed) pref(c).edit().putString(KEY, next.toString()).apply();
    }

    /** 返回全部供应商（顺序稳定）。 */
    public static List<Provider> list(Context c) {
        seedDefaults(c);
        List<Provider> out = new ArrayList<>();
        String json = pref(c).getString(KEY, "");
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Provider(o.optString("id", ""), o.optString("name", ""),
                        o.optString("type", ""), o.optString("baseUrl", ""),
                        o.optString("apiKey", "")));
            }
        } catch (Exception ignore) {}
        return out;
    }

    public static Provider getById(Context c, String id) {
        for (Provider p : list(c)) if (p.id.equals(id)) return p;
        return null;
    }

    /** 是否存在该 id。 */
    public static boolean hasId(Context c, String id) {
        return getById(c, id) != null;
    }

    /** 新增或按 id 覆盖。返回 true=新增，false=覆盖。 */
    public static boolean put(Context c, Provider p) {
        if (p == null || p.id == null || p.id.trim().isEmpty()) return false;
        JSONArray arr;
        try { arr = new JSONArray(pref(c).getString(KEY, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        boolean replaced = false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && p.id.equals(o.optString("id", ""))) { replaced = true; continue; }
            next.put(o);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("type", p.type);
            o.put("baseUrl", p.baseUrl);
            o.put("apiKey", p.apiKey == null ? "" : p.apiKey);
            next.put(o);
            pref(c).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignore) {}
        return !replaced;
    }

    /** 删除一个供应商。 */
    public static void remove(Context c, String id) {
        JSONArray arr;
        try { arr = new JSONArray(pref(c).getString(KEY, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) continue;
            next.put(o);
        }
        pref(c).edit().putString(KEY, next.toString()).apply();
    }
}
