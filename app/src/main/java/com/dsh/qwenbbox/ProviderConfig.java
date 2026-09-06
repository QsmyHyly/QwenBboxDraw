package com.dsh.qwenbbox;

/**
 * 百炼 (DashScope) OpenAI 兼容配置。本 App 固定走百炼，模型默认 qwen3.8-flash。
 * 密钥来自 tools/keys.local（个人测试用，勿随包分发）；可在 App 内覆盖。
 */
public class ProviderConfig {

    /** 百炼 OpenAI 兼容端点（不含 /chat/completions）。 */
    public static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 默认模型（按需求从 qwen3-vl-plus 换成 qwen3.8-flash）。 */
    public static final String DEFAULT_MODEL = "qwen3.8-flash";

    /** 百炼提供商 id（VisionApiClient 用它决定关思考字段 = enable_thinking:false）。 */
    public static final String PROVIDER_ID = "bailian";

    /** 供应商 id 常量（VisionApiClient.applyThinkingParam 按此分支写关思考字段）。 */
    public static final String BAILIAN_ID = "bailian";
    public static final String DEEPSEEK_ID = "deepseek";

    /**
     * 内置默认 key：出于隐私考虑，源码不再写入真实密钥。
     * 请在 App 的「⚙ 管理供应商」里为各供应商填入各自的 API Key（保存在设备本地，不入库）。
     */
    public static final String DEFAULT_API_KEY = "";

    // —————— 高级参数默认值（SettingsStore 兜底；UI 可覆盖）——————

    /** 单次请求超时（毫秒）。 */
    public static final int DEFAULT_TIMEOUT_MS = 90_000;

    /** 首次失败后再重试次数（0 = 不重试）。 */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** 指数退避的基准延时（毫秒）。 */
    public static final long DEFAULT_BACKOFF_MS = 1_000L;
}
