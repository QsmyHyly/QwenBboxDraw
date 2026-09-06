package com.dsh.qwenbbox.api;

import com.dsh.qwenbbox.ProviderConfig;
import com.dsh.qwenbbox.log.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 调用 OpenAI 兼容 chat/completions 接口的多模态视觉客户端（DeepSeek / 百炼 DashScope 通用）。
 * 仅依赖 JDK 网络 + org.json，无第三方库。
 *
 * <p>默认走 <b>流式（SSE，stream=true）</b>传输：服务端把 token 逐片推送，客户端持续读取以
 * 保持连接活跃，从而避免「输出太长 / 思考过久导致一次性返回读超时、连接被中断」的问题。
 * 但在代码层面始终<b>先把流式返回攒完整</b>，再一次返回给调用方展示（不逐字刷新 UI）。</p>
 *
 * <p>内置「指数退避 + 抖动」重试：对可重试的瞬时错误（HTTP 429 / 5xx、网络异常、超时）
 * 自动重试最多 maxRetries 次；对客户端错误（401/400/403 等）不重试，直接失败。</p>
 */
public class VisionApiClient {

    /** 单次退避上限（毫秒），指数增长不会超过它。 */
    public static final long MAX_BACKOFF_MS = 30_000L;

    private final String apiKey;
    private final String baseUrl;   // 例如 https://dashscope.aliyuncs.com/compatible-mode/v1
    private final String model;
    /** 提供商 id（deepseek / bailian / custom），用于决定「关思考」时写入的请求体字段。 */
    private final String provider;
    /** 是否启用「思考（深度推理）」。默认两家均关。 */
    private final boolean enableThinking;

    /** 首次请求失败后最多再重试的次数（0 = 不重试）。 */
    private final int maxRetries;
    /** 指数退避的基准延时（毫秒），第 k 次退避约 base * 2^k。 */
    private final long baseBackoffMs;

    private final Random rnd = new Random();

    public VisionApiClient(String apiKey, String baseUrl, String model, String provider, boolean enableThinking) {
        this(apiKey, baseUrl, model, 2, 1000L, provider, enableThinking);
    }

    public VisionApiClient(String apiKey, String baseUrl, String model,
                           int maxRetries, long baseBackoffMs, String provider, boolean enableThinking) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.provider = provider == null ? ProviderConfig.DEEPSEEK_ID : provider;
        this.enableThinking = enableThinking;
        this.maxRetries = Math.max(0, maxRetries);
        this.baseBackoffMs = Math.max(0, baseBackoffMs);
    }

    /**
     * 发送一张图（dataUrl，形如 data:image/jpeg;base64,xxxx）+ 文本来做图生文分析，带重试。
     * 内部使用流式(stream=true)传输，但会把完整响应攒好再一次性返回，避免长输出/长思考导致读超时。
     *
     * <p>说明：本测试 App 按「原图直传」原则——不裁剪、不缩放，直接把图片 base64 发给模型，
     * 由模型自行处理。systemPrompt 可为空（百炼文档建议把要求放 User Message，故默认空 system）。</p>
     *
     * @return 模型返回的完整 content 字符串（JSON 或文本）。
     */
    public String chatWithImage(String systemPrompt, String userText,
                                String imageDataUrl, int timeoutMs)
            throws IOException, org.json.JSONException {
        int attempt = 0;
        while (true) {
            try {
                return chatWithImageOnce(systemPrompt, userText, imageDataUrl, timeoutMs);
            } catch (ApiException e) {
                AppLog.w("Vision", "请求失败（第 " + attempt + " 次，可重试=" + e.retryable + "，status=" + e.statusCode + "）：" + e.getMessage());
                if (!e.retryable || attempt >= maxRetries) {
                    AppLog.e("Vision", "最终失败（不再重试）：" + e.getMessage());
                    throw e;
                }
                long delay = e.retryAfterMs > 0 ? e.retryAfterMs : jitteredBackoffMs(attempt);
                AppLog.i("Vision", "退避 " + delay + "ms 后重试（第 " + (attempt + 1) + " 次）");
                sleep(delay);
                attempt++;
            }
        }
    }

    /** 单次实际请求（不含重试），走流式(stream=true)。成功返回完整 content；失败抛 ApiException。 */
    private String chatWithImageOnce(String systemPrompt, String userText,
                                     String imageDataUrl, int timeoutMs)
            throws IOException, org.json.JSONException {
        JSONArray messages = new JSONArray();

        // system 消息仅在非空时加入（百炼建议把要求放 User Message，故默认不传 system）
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
            messages.put(system);
        }

        JSONArray userContent = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", userText == null ? "" : userText);
        userContent.put(textPart);

        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        JSONObject urlObj = new JSONObject();
        urlObj.put("url", imageDataUrl);
        imagePart.put("image_url", urlObj);
        userContent.put(imagePart);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userContent);
        messages.put(user);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", true);
        body.put("messages", messages);
        applyThinkingParam(body);

        AppLog.i("Vision", "发起请求 provider=" + provider + "，model=" + model
                + "，端点=" + baseUrl + "/chat/completions"
                + "，图 dataUrl 长度=" + imageDataUrl.length() + "，think=" + enableThinking
                + "，超时=" + timeoutMs + "ms，prompt 长度=" + (userText == null ? 0 : userText.length()));
        AppLog.i("Vision", "请求体：" + redactBodyForLog(body));
        AppLog.i("Vision", "使用 key=" + keyIdentity(apiKey));

        URL url = new URL(baseUrl + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            AppLog.i("Vision", "HTTP 状态码=" + code);
            if (code < 200 || code >= 300) {
                InputStream es = conn.getErrorStream();
                String resp = readStream(es);
                long retryAfter = parseRetryAfterMs(conn.getHeaderField("Retry-After"));
                throw new ApiException(code, isRetryableStatus(code), "HTTP " + code + ": " + resp, retryAfter);
            }
            String resp = readStream(conn.getInputStream());
            String content = parseStreamingContent(resp);
            AppLog.i("Vision", "请求成功，content 长度=" + (content == null ? 0 : content.length()));
            AppLog.i("Vision", "返回内容：" + truncate(content, 3000));
            return content;
        } catch (java.net.SocketTimeoutException e) {
            throw new ApiException(0, true, "请求超时（" + timeoutMs + "ms）：" + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(0, true, "网络错误：" + e.getMessage());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 拉取模型列表（OpenAI 兼容 GET /models，百炼/DeepSeek 均支持）。
     * 返回模型 id 列表；若端点不兼容或未登录则抛异常。
     */
    public static List<String> fetchModelList(String apiKey, String baseUrl, int timeoutMs)
            throws IOException, org.json.JSONException {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/models");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readStream(conn.getErrorStream());
                throw new IOException("HTTP " + code + ": " + err);
            }
            String resp = readStream(conn.getInputStream());
            return parseModelIds(resp);
        } finally {
            conn.disconnect();
        }
    }

    /** 解析 OpenAI 兼容 {@code {"data":[{"id":"..."}]}} 模型列表。 */
    public static List<String> parseModelIds(String body) throws org.json.JSONException {
        List<String> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return out;
        JSONObject obj = new JSONObject(body);
        JSONArray data = obj.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m != null) {
                    String id = m.optString("id", "");
                    if (!id.isEmpty()) out.add(id);
                }
            }
        }
        return out;
    }

    /**
     * 关闭思考（enableThinking=false）时，按供应商写入对应请求体字段（实测确认）：
     * <ul>
     *   <li>百炼 qwen（hybrid thinking）：{@code enable_thinking=false}</li>
     *   <li>DeepSeek（思考型）：{@code thinking={"type":"disabled"}}（enable_thinking 无效）</li>
     *   <li>自定义：不写入，交给模型默认</li>
     * </ul>
     * 若启用思考，则不写入（模型默认开启思考）。
     */
    private void applyThinkingParam(JSONObject body) throws org.json.JSONException {
        if (enableThinking) return;
        if (ProviderConfig.BAILIAN_ID.equals(provider)) {
            body.put("enable_thinking", false);
        } else if (ProviderConfig.DEEPSEEK_ID.equals(provider)) {
            JSONObject t = new JSONObject();
            t.put("type", "disabled");
            body.put("thinking", t);
        }
    }

    /**
     * 解析流式(SSE)响应，返回累加起来的完整 content。
     *
     * <p>格式（OpenAI 兼容，DeepSeek / 百炼均实测确认）：</p>
     * <pre>
     * data: {"choices":[{"delta":{"content":"你"},"index":0}]}
     * data: {"choices":[{"delta":{"content":"好"},"index":0}]}
     * data: [DONE]
     * </pre>
     * 只累加 {@code choices[0].delta.content}（可见答案）；忽略 {@code delta.reasoning_content}
     * （思考模型的思维过程）；跳过 JSON null / 空串；遇到 {@code data: [DONE]} 结束。
     * 若整段没有 data: 前缀（服务端可能忽略了 stream 参数），回退按普通 JSON
     * {@code choices[0].message.content} 解析。
     *
     * @throws IOException SSE 数据里含 error 字段时抛出（携带错误说明）。
     */
    public static String parseStreamingContent(String body) throws IOException, org.json.JSONException {
        if (body == null || body.trim().isEmpty()) return "";
        StringBuilder acc = new StringBuilder();
        boolean sawData = false;
        BufferedReader br = new BufferedReader(new StringReader(body));
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("data:")) {
                sawData = true;
                String payload = t.substring(5).trim();
                if (payload.isEmpty()) continue;
                if (payload.equals("[DONE]")) break;
                JSONObject obj = new JSONObject(payload);
                if (obj.has("error")) {
                    JSONObject eo = obj.optJSONObject("error");
                    String err = eo != null ? eo.toString() : obj.optString("error", "");
                    throw new IOException("stream error: " + err);
                }
                JSONArray choices = obj.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject msg = choices.optJSONObject(0);
                    JSONObject delta = msg != null ? msg.optJSONObject("delta") : null;
                    if (delta != null) {
                        Object cobj = delta.opt("content");
                        if (cobj instanceof String) {
                            String c = (String) cobj;
                            if (!c.isEmpty()) acc.append(c);
                        }
                    }
                }
            }
        }
        if (sawData) return acc.toString();
        // 非 SSE：按普通 JSON 解析
        JSONObject obj = new JSONObject(body);
        JSONArray choices = obj.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject msg = choices.optJSONObject(0);
            JSONObject message = msg != null ? msg.optJSONObject("message") : null;
            if (message != null) {
                Object cobj = message.opt("content");
                if (cobj instanceof String) return (String) cobj;
            }
        }
        return "";
    }

    /**
     * 生成用于日志的请求体字符串：把图片 dataUrl 的 base64 截断（保留前 40 字符 + 长度提示），
     * 避免日志被几 KB 的 base64 撑爆；system/user 文本提示词完整保留（这正是排查需要看的部分）。
     */
    private static String redactBodyForLog(JSONObject body) {
        try {
            JSONObject copy = new JSONObject(body.toString());
            JSONArray messages = copy.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject m = messages.optJSONObject(i);
                    if (m == null) continue;
                    Object content = m.opt("content");
                    if (content instanceof JSONArray) {
                        JSONArray parts = (JSONArray) content;
                        for (int j = 0; j < parts.length(); j++) {
                            JSONObject p = parts.optJSONObject(j);
                            if (p == null) continue;
                            if ("image_url".equals(p.optString("type", ""))) {
                                JSONObject urlObj = p.optJSONObject("image_url");
                                if (urlObj != null) {
                                    String url = urlObj.optString("url", "");
                                    if (url.length() > 48) {
                                        urlObj.put("url", url.substring(0, 40) + "...(" + url.length() + " chars)");
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return copy.toString();
        } catch (Exception e) {
            return body.toString();
        }
    }

    /** 截断字符串用于日志输出，超出 max 加截断提示。 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(共" + s.length() + "字符，已截断)";
    }

    /** 用于日志的 key 身份标识（脱敏：仅前8+后4字符 + 长度，不暴露完整密钥，足以区分用的是哪把 key）。 */
    public static String keyIdentity(String key) {
        if (key == null || key.isEmpty()) return "(空)";
        int n = key.length();
        if (n <= 12) return key.substring(0, Math.min(6, n)) + "...(" + n + "字符)";
        return key.substring(0, 8) + "…" + key.substring(n - 4) + "(" + n + "字符)";
    }

    /** 判断某个 HTTP 状态码是否属于「可重试」的瞬时错误。 */
    public static boolean isRetryableStatus(int code) {
        return code == 429 || code >= 500;
    }

    /** 指数退避的名义延时（毫秒）：base * 2^attempt，封顶 MAX_BACKOFF_MS。 */
    public static long nominalBackoffMs(int attempt, long baseMs) {
        if (baseMs <= 0) return 0;
        int shift = Math.min(Math.max(attempt, 0), 30);
        long d = baseMs * (1L << shift);
        if (d <= 0) return MAX_BACKOFF_MS;
        return Math.min(d, MAX_BACKOFF_MS);
    }

    /** 指数退避中实际睡眠的时间（带 0.5x~1.0x 随机抖动，避免多线程同时重试）。 */
    private long jitteredBackoffMs(int attempt) {
        long nominal = nominalBackoffMs(attempt, baseBackoffMs);
        if (nominal <= 0) return 0;
        double j = 0.5 + rnd.nextDouble() * 0.5;  // 0.5x .. 1.0x
        long d = (long) (nominal * j);
        return d > 0 ? d : 1;
    }

    /** 解析服务端 Retry-After 头（整数秒），解析失败或无则返回 0。 */
    private static long parseRetryAfterMs(String v) {
        if (v == null) return 0;
        String s = v.trim();
        try {
            long sec = Long.parseLong(s);
            if (sec <= 0) return 0;
            long ms = sec * 1000L;
            return ms <= 0 ? 0 : Math.min(ms, MAX_BACKOFF_MS);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 携带 HTTP 状态码与「是否可重试」标记的网络/HTTP 异常。 */
    public static class ApiException extends IOException {
        final int statusCode;    // 0 表示纯网络异常（无 HTTP 状态码）
        final boolean retryable;
        final long retryAfterMs; // 服务端建议的重试等待毫秒；0 表示无
        public ApiException(int statusCode, boolean retryable, String msg) {
            this(statusCode, retryable, msg, 0);
        }
        public ApiException(int statusCode, boolean retryable, String msg, long retryAfterMs) {
            super(msg);
            this.statusCode = statusCode;
            this.retryable = retryable;
            this.retryAfterMs = Math.max(0, retryAfterMs);
        }
    }
}
