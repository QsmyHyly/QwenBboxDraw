package com.dsh.qwenbbox;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词模板管理：以 {@code {name, content, updatedAt}} 的 JSON 数组形式存进
 * SharedPreferences。支持 列表 / 保存 / 覆盖 / 删除 / 复制，且同名覆盖、复制自动去重命名。
 */
public class TemplateStore {
    private static final String PREFS = "qwen_bbox_templates";
    private static final String KEY = "templates_json";
    private static final String KEY_SEEDED = "seeded_v1";

    /** 首次初始化时预置的默认模板（仅在未打过初始化标记时写一次）。 */
    private static final String[][] DEFAULT_TEMPLATES = {
            {"查找图片中的黑丝",
             "识别图片中的人物所穿的黑色丝袜（黑丝），并以JSON格式输出其bbox的坐标及其中文名称"},
            {"查找图片中的人脸",
             "识别图片中的所有人物人脸，并以JSON格式输出其bbox的坐标及其中文名称"},
    };

    /** 单条模板。 */
    public static class Template {
        public final String name;
        public final String content;
        public Template(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 若从未初始化过，则写入默认模板并打上标记（幂等，仅首次生效）。 */
    private static void seedDefaults(Context c) {
        SharedPreferences p = pref(c);
        if (p.getBoolean(KEY_SEEDED, false)) return;
        for (String[] d : DEFAULT_TEMPLATES) put(c, d[0], d[1]);
        p.edit().putBoolean(KEY_SEEDED, true).apply();
    }

    /** 返回所有模板（按保存先后顺序）。 */
    public static List<Template> list(Context c) {
        seedDefaults(c);
        List<Template> out = new ArrayList<>();
        String json = pref(c).getString(KEY, "");
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Template(o.optString("name", ""), o.optString("content", "")));
            }
        } catch (Exception e) {
            // 损坏数据：忽略，当作无模板
        }
        return out;
    }

    /** 是否存在同名模板。 */
    public static boolean has(Context c, String name) {
        for (Template t : list(c)) if (t.name.equals(name)) return true;
        return false;
    }

    /**
     * 保存（新增或同名覆盖）一条模板。name 为空返回 false。
     * 返回 true 表示新增，false 表示覆盖了已有同名模板。
     */
    public static boolean put(Context c, String name, String content) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        boolean replaced = false;
        JSONArray arr = new JSONArray();
        try {
            arr = new JSONArray(pref(c).getString(KEY, "[]"));
        } catch (Exception ignore) { arr = new JSONArray(); }
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name", ""))) { replaced = true; continue; }
            next.put(o);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("content", content == null ? "" : content);
            o.put("updatedAt", System.currentTimeMillis());
            next.put(o);
            pref(c).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignore) {}
        return !replaced;
    }

    /** 覆盖已存在模板的内容；不存在则新增。 */
    public static void overwrite(Context c, String name, String content) {
        put(c, name, content);
    }

    /** 删除一条模板。 */
    public static void remove(Context c, String name) {
        JSONArray arr;
        try { arr = new JSONArray(pref(c).getString(KEY, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString("name", ""))) continue;
            next.put(o);
        }
        pref(c).edit().putString(KEY, next.toString()).apply();
    }

    /**
     * 复制一份模板：内容相同，名字自动去重成 {@code <原名> 副本}、{@code <原名> 副本2}…。
     * 返回新模板名；若原模板不存在返回 null。
     */
    public static String duplicate(Context c, String name) {
        List<Template> all = list(c);
        String content = null;
        for (Template t : all) if (t.name.equals(name)) { content = t.content; break; }
        if (content == null) return null;
        String base = name + " 副本";
        String newName = base;
        int n = 2;
        while (has(c, newName)) { newName = base + n; n++; }
        put(c, newName, content);
        return newName;
    }

    /**
     * 重命名：把原模板（含内容）改名为 newName。若原模板不存在返回 false；
     * 若新名字为空、等于原名、或已被占用则返回 false。
     */
    public static boolean rename(Context c, String oldName, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        if (oldName.equals(newName)) return false;
        if (has(c, newName)) return false;
        List<Template> all = list(c);
        String content = null;
        for (Template t : all) if (t.name.equals(oldName)) { content = t.content; break; }
        if (content == null) return false;
        remove(c, oldName);
        put(c, newName.trim(), content);
        return true;
    }
}
