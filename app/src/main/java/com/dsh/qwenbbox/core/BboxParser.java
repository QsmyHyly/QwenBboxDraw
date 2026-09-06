package com.dsh.qwenbbox.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 忠实移植 qwen3-vl-2d.py 的解析逻辑（{@code parse_json} + {@code plot_bounding_boxes} 里的解析部分）：
 * <ol>
 *   <li>剥离 markdown {@code ```json} 围栏（与 Python {@code parse_json} 一致）；</li>
 *   <li>把返回解析成 list，每项含 {@code bbox_2d: [x1, y1, x2, y2]}（归一化到 [0,1000]）+ {@code label}；</li>
 *   <li>解析失败时容错：用正则把 {@code "bbox_2d":[x1,y1,x2,y2]} 及其附近的 label 抠出来，
 *       对应 Python 里 {@code ast.literal_eval} 失败后找 {@code '"}'} 截断补 {@code ]} 的兜底思路。</li>
 * </ol>
 *
 * <p><b>坐标约定</b>（照搬 Python）：{@code bbox_2d = [x1, y1, x2, y2]}，{@code abs_x1 = bbox_2d[0]/1000*width}，
 * {@code abs_y1 = bbox_2d[1]/1000*height}，{@code abs_x2 = bbox_2d[2]/1000*width}，
 * {@code abs_y2 = bbox_2d[3]/1000*height}；若 x1>x2 或 y1>y2 互换。除以 <b>1000</b>（非 999）。</p>
 *
 * <p>纯逻辑（不依赖 Android），便于 JVM 单测。</p>
 */
public class BboxParser {

    /** 一个被定位物体：标签 + 原始 bbox_2d [x1,y1,x2,y2]（0~1000，未换算）。 */
    public static class Item {
        /** 物体中文名（label）；模型未给出则为空。 */
        public String label = "";
        /** 原始 bbox_2d [x1,y1,x2,y2]，归一化到 [0,1000]。 */
        public float[] bbox2d = null;
        public Item() { }
        public Item(String label, float[] bbox2d) {
            this.label = label == null ? "" : label;
            this.bbox2d = bbox2d;
        }
    }

    /**
     * 从模型返回文本中解析出 bbox_2d 物体列表（忠实移植 Python parse_json + literal_eval）。
     * @param raw 模型返回原文。
     * @return 物体列表（无则空），每个含 label + bbox_2d[4]（原始 0~1000 值）。
     */
    public static List<Item> parse(String raw) {
        List<Item> out = new ArrayList<Item>();
        if (raw == null || raw.trim().isEmpty()) return out;

        // 1) 剥离 ```json 围栏（Python parse_json）
        String json = stripFence(raw);

        // 2) 严格解析（整段是 JSON 数组 / 或含 objects/elements 等数组字段）
        List<Item> strict = tryStrict(json);
        if (!strict.isEmpty()) return strict;

        // 3) 容错：整段畸形时，用正则把每个 bbox_2d 及其附近的 label 抠出（对应 Python 兜底截断）
        return lenientItems(json);
    }

    /**
     * 剥离 markdown ```json 围栏 —— 忠实移植 Python parse_json：
     * 逐行扫描，遇到内容恰好为 "```json" 的行，取其后所有行，再按 "```" 截断取前半。
     * 也兼容单行 ```json ... ``` 与无围栏的情况。
     */
    static String stripFence(String raw) {
        String s = raw;
        // 兼容：```json 紧跟内容在同一行
        String[] lines = s.split("\n", -1);
        int fenceStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("```json")) { fenceStart = i; break; }
        }
        if (fenceStart >= 0) {
            // 取 fenceStart 之后所有行，再按 ``` 截断
            StringBuilder sb = new StringBuilder();
            for (int i = fenceStart + 1; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            s = sb.toString();
            int end = s.indexOf("```");
            if (end >= 0) s = s.substring(0, end);
            return s;
        }
        // 无 ```json 行：尝试去掉任意成对 ``` 围栏
        int a = s.indexOf("```");
        if (a >= 0) {
            int b = s.indexOf("```", a + 3);
            if (b > a) {
                // 取两围栏之间内容
                String inner = s.substring(a + 3, b);
                // 去掉可能的语言标记前缀（json 等）
                int nl = inner.indexOf('\n');
                if (nl >= 0 && inner.substring(0, nl).trim().matches("[a-zA-Z]+")) {
                    inner = inner.substring(nl + 1);
                }
                return inner;
            }
        }
        return s;
    }

    /** 严格解析：尝试把整段当 JSON 解析。兼容数组直接给出、或包在 objects/elements/findings 字段里。 */
    private static List<Item> tryStrict(String json) {
        List<Item> out = new ArrayList<Item>();
        String s = json.trim();
        if (s.isEmpty()) return out;
        try {
            // 直接是数组：[ {...}, {...} ]
            if (s.startsWith("[")) {
                JSONArray arr = new JSONArray(s);
                collectItems(arr, out);
                return out;
            }
            // 是对象：取其中的 objects/elements/findings 数组
            if (s.startsWith("{")) {
                JSONObject o = new JSONObject(s);
                JSONArray arr = firstArray(o, "objects", "elements", "findings", "results", "items");
                if (arr != null) {
                    collectItems(arr, out);
                    return out;
                }
                // 顶层可能就是单个对象（含 bbox_2d）
                Item it = itemOf(o);
                if (it != null) out.add(it);
            }
        } catch (Exception e) {
            // 整段不合法 → 容错
        }
        return out;
    }

    /** 从一个数组里收集所有含 bbox_2d 的对象。 */
    private static void collectItems(JSONArray arr, List<Item> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = itemOf(o);
            if (it != null) out.add(it);
        }
    }

    /** 从一个对象里取 bbox_2d + label，构造 Item；无 bbox_2d 返回 null。 */
    private static Item itemOf(JSONObject o) {
        float[] b = toBbox(o.optJSONArray("bbox_2d"));
        if (b == null) b = toBbox(o.optJSONArray("bbox"));   // 兼容个别模型用 bbox
        if (b == null) return null;
        String label = firstNonEmpty(o, "label", "name", "description", "type");
        return new Item(label, b);
    }

    private static float[] toBbox(JSONArray arr) {
        if (arr == null || arr.length() < 4) return null;
        try {
            float[] b = new float[4];
            for (int i = 0; i < 4; i++) {
                double d = arr.getDouble(i);
                if (Double.isNaN(d) || Double.isInfinite(d)) return null;
                b[i] = (float) d;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray firstArray(JSONObject o, String... keys) {
        for (String k : keys) {
            JSONArray a = o.optJSONArray(k);
            if (a != null && a.length() > 0) return a;
        }
        return null;
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    // —————— 容错：正则抠 bbox_2d + 附近 label ——————

    /** 抓 "label":"xxx" 与其后的 "bbox_2d":[x1,y1,x2,y2]（同一对象内）。 */
    private static final Pattern LABEL_THEN_BBOX = Pattern.compile(
            "\"(?:label|name|description|type)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
            + "[^}]*?"
            + "\"bbox_2d\"\\s*:?\\s*\\[+\\s*([0-9.,\\s-]+)");

    /** 抓 "bbox_2d":[x1,y1,x2,y2] 与其后的 label（顺序反过来的情况）。 */
    private static final Pattern BBOX_THEN_LABEL = Pattern.compile(
            "\"bbox_2d\"\\s*:?\\s*\\[+\\s*([0-9.,\\s-]+)\\s*\\]"
            + "[^}]*?"
            + "\"(?:label|name|description|type)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 纯 bbox_2d 兜底（无 label）。 */
    private static final Pattern BBOX_ONLY = Pattern.compile(
            "\"bbox_2d\"\\s*:?\\s*\\[+\\s*([0-9.,\\s-]+)");

    private static List<Item> lenientItems(String json) {
        List<Item> out = new ArrayList<Item>();
        if (json == null) return out;
        // 先尝试 label 在前
        Matcher m = LABEL_THEN_BBOX.matcher(json);
        while (m.find()) {
            float[] b = parseNums(m.group(2));
            if (b != null) out.add(new Item(m.group(1).trim(), b));
        }
        if (!out.isEmpty()) return out;
        // 再尝试 bbox 在前、label 在后
        Matcher m2 = BBOX_THEN_LABEL.matcher(json);
        while (m2.find()) {
            float[] b = parseNums(m2.group(1));
            if (b != null) out.add(new Item(m2.group(2).trim(), b));
        }
        if (!out.isEmpty()) return out;
        // 纯 bbox_2d 兜底（无 label）
        Matcher m3 = BBOX_ONLY.matcher(json);
        while (m3.find()) {
            float[] b = parseNums(m3.group(1));
            if (b != null) out.add(new Item("", b));
        }
        return out;
    }

    private static float[] parseNums(String s) {
        if (s == null) return null;
        String[] parts = s.split("[,\\s]+");
        List<Float> nums = new ArrayList<Float>();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            try {
                nums.add(Float.parseFloat(p.trim()));
            } catch (NumberFormatException e) { /* 忽略 */ }
        }
        if (nums.size() < 4) return null;
        float[] b = new float[4];
        for (int i = 0; i < 4; i++) b[i] = nums.get(i);
        return b;
    }
}
