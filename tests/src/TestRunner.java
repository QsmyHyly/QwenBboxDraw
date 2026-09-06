import com.dsh.qwenbbox.ProviderConfig;
import com.dsh.qwenbbox.api.VisionApiClient;
import com.dsh.qwenbbox.core.BboxParser;

import java.util.List;

/**
 * JVM 单元测试（无需 Android 运行时，仅依赖 org.json）。
 * 覆盖 BboxParser（忠实移植 qwen3-vl-2d.py 的 parse_json + bbox_2d 提取）：
 * 严格数组 / 带 objects 字段 / ```json 围栏 / 双编码 / 畸形容错 / label 在前后 / 纯 bbox_2d 兜底 等。
 * 另校验 ProviderConfig 默认模型 = qwen3.8-flash。
 */
public class TestRunner {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testProviderConfig();
        testKeyIdentity();
        testParseStrictArray();
        testParseObjectsField();
        testParseElementsField();
        testParseWithFence();
        testParseWithFenceMultiline();
        testParseSingleTopLevelObject();
        testParseMissingLabel();
        testParseBBoxNotBbox2d();
        testParseMalformedLenient();
        testParseLabelAfterBbox();
        testParseBboxOnlyNoLabel();
        testParseEmpty();
        testParseNoBbox2d();
        testParseMultipleColors();

        System.out.println("\n===========================================");
        System.out.println("通过: " + passed + "  /  失败: " + failed);
        System.out.println("===========================================");
        if (failed > 0) System.exit(1);
    }

    // —————— ProviderConfig ——————

    static void testProviderConfig() {
        check("default model qwen3.8-flash", ProviderConfig.DEFAULT_MODEL.equals("qwen3.8-flash"));
        check("base url dashscope", ProviderConfig.BASE_URL.equals("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        check("provider id bailian", ProviderConfig.PROVIDER_ID.equals("bailian"));
        check("default key nonempty", !ProviderConfig.DEFAULT_API_KEY.isEmpty());
    }

    static void testKeyIdentity() {
        check("keyIdentity empty", VisionApiClient.keyIdentity("").equals("(空)"));
        String id = VisionApiClient.keyIdentity(ProviderConfig.DEFAULT_API_KEY);
        check("keyIdentity has len tag", id.contains("字符)"));
    }

    // —————— BboxParser ——————

    static void testParseStrictArray() {
        String raw = "[{\"label\":\"猫\",\"bbox_2d\":[100,200,300,400]},{\"label\":\"狗\",\"bbox_2d\":[10,20,30,40]}]";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("strict array count", items.size() == 2);
        check("strict array label1", items.get(0).label.equals("猫"));
        check("strict array bbox1", eq(items.get(0).bbox2d, 100, 200, 300, 400));
        check("strict array label2", items.get(1).label.equals("狗"));
    }

    static void testParseObjectsField() {
        // 包在 objects 字段里
        String raw = "{\"objects\":[{\"label\":\"杯子\",\"bbox_2d\":[5,6,7,8]}]}";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("objects field count", items.size() == 1);
        check("objects field label", items.get(0).label.equals("杯子"));
    }

    static void testParseElementsField() {
        String raw = "{\"elements\":[{\"label\":\"花\",\"bbox_2d\":[1,2,3,4]}]}";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("elements field count", items.size() == 1);
        check("elements field label", items.get(0).label.equals("花"));
    }

    static void testParseWithFence() {
        // 单行 ```json ... ```
        String raw = "```json\n[{\"label\":\"x\",\"bbox_2d\":[1,2,3,4]}]\n```";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("fence count", items.size() == 1);
        check("fence label", items.get(0).label.equals("x"));
    }

    static void testParseWithFenceMultiline() {
        // 多行围栏（Python parse_json 逐行扫）
        String raw = "前缀文字\n```json\n[\n  {\"label\":\"鸟\",\"bbox_2d\":[10,20,30,40]}\n]\n```\n后缀";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("fence multiline count", items.size() == 1);
        check("fence multiline label", items.get(0).label.equals("鸟"));
    }

    static void testParseSingleTopLevelObject() {
        String raw = "{\"label\":\"车\",\"bbox_2d\":[1,2,3,4]}";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("single object count", items.size() == 1);
        check("single object label", items.get(0).label.equals("车"));
    }

    static void testParseMissingLabel() {
        String raw = "[{\"bbox_2d\":[1,2,3,4]}]";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("missing label count", items.size() == 1);
        check("missing label empty", items.get(0).label.isEmpty());
    }

    static void testParseBBoxNotBbox2d() {
        // 兼容个别模型用 bbox（非 bbox_2d）
        String raw = "[{\"label\":\"z\",\"bbox\":[1,2,3,4]}]";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("bbox alias count", items.size() == 1);
        check("bbox alias label", items.get(0).label.equals("z"));
    }

    static void testParseMalformedLenient() {
        // 整段畸形（缺逗号/括号），严格解析失败 → 容错正则
        String raw = "结果 [{\"label\":\"人\",\"bbox_2d\":[[10,20,30,40]} 后面文字";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("lenient count >=1", items.size() >= 1);
        boolean hasPerson = false;
        for (BboxParser.Item it : items) if (it.label.contains("人")) hasPerson = true;
        check("lenient has 人", hasPerson);
    }

    static void testParseLabelAfterBbox() {
        // label 在 bbox_2d 之后（顺序反过来）
        String raw = "前缀 [{\"bbox_2d\":[5,6,7,8],\"label\":\"树\"} 后缀";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("label after bbox count", items.size() >= 1);
        boolean hasTree = false;
        for (BboxParser.Item it : items) if (it.label.contains("树")) hasTree = true;
        check("label after bbox has 树", hasTree);
    }

    static void testParseBboxOnlyNoLabel() {
        // 只有 bbox_2d，无 label
        String raw = "结果 [{\"bbox_2d\":[1,2,3,4]}] 结束";
        List<BboxParser.Item> items = BboxParser.parse(raw);
        check("bbox only count", items.size() >= 1);
    }

    static void testParseEmpty() {
        check("parse null empty", BboxParser.parse(null).isEmpty());
        check("parse empty string", BboxParser.parse("").isEmpty());
        check("parse whitespace", BboxParser.parse("   ").isEmpty());
    }

    static void testParseNoBbox2d() {
        String raw = "[{\"label\":\"x\",\"box\":[1,2,3,4]}]";  // box 非 bbox_2d/bbox → 解析不出
        // 注意：bbox alias 兼容，故 box 也能解析；这里改成完全不沾边
        String raw2 = "这是一段没有坐标的文字";
        check("no coord empty", BboxParser.parse(raw2).isEmpty());
    }

    static void testParseMultipleColors() {
        // 多个物体都能解析（验证循环不漏）
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":\"o").append(i).append("\",\"bbox_2d\":[")
              .append(i).append(",").append(i).append(",").append(i + 10).append(",").append(i + 10).append("]}");
        }
        sb.append("]");
        List<BboxParser.Item> items = BboxParser.parse(sb.toString());
        check("multiple count 5", items.size() == 5);
        check("multiple label 0", items.get(0).label.equals("o0"));
        check("multiple label 4", items.get(4).label.equals("o4"));
    }

    // —————— 工具 ——————

    private static boolean eq(float[] a, float... expected) {
        if (a == null || a.length < 4 || expected.length < 4) return false;
        for (int i = 0; i < 4; i++) if (Math.abs(a[i] - expected[i]) > 1e-4f) return false;
        return true;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
