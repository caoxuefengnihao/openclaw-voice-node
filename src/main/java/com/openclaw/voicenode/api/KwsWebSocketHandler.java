package com.openclaw.voicenode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.service.ChatBridgeService;
import com.openclaw.voicenode.service.ChatSessionHandle;
import com.openclaw.voicenode.service.KwsService;
import com.openclaw.voicenode.service.SttService;
import com.openclaw.voicenode.service.TtsService;
import com.openclaw.voicenode.service.VadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KWS 独立 WS Handler (v3 新增),挂 {@code /ws/kws} 端点。
 *
 * <p><b>完全独立于 {@link VoiceWebSocketHandler}</b>,不碰现有 v2 代码。
 *
 * <p>两种模式(由 session attribute {@code mode} 区分):
 * <ul>
 *   <li><b>KWS 监听模式 ({@code mode="kws"})</b>: binary 帧送 {@link KwsService#acceptFrame},
 *       检测到唤醒词 -> 发 {@code wake.detected} 事件,自动切到录音模式</li>
 *   <li><b>录音对话模式 ({@code mode="recording"})</b>: binary 帧累积到 buffer,
 *       {@code audio.end} 时 -> 调 {@link SttService#recognize} -> 调
 *       {@link ChatBridgeService#open} 开 session -> 调
 *       {@link ChatSessionHandle#sendText} 发文本给 gateway ->
 *       turn.done 时调 {@link TtsService#synthesize} 发 {@code assistant.audio}</li>
 * </ul>
 *
 * <p>复用的现有 service (只调 public 方法,不改):
 * <ul>
 *   <li>{@link SttService#recognize(byte[])} — PCM 转文本</li>
 *   <li>{@link TtsService#synthesize(String)} — 文本转 MP3</li>
 *   <li>{@link ChatBridgeService#open(WebSocketSession)} — 开 chat session</li>
 *   <li>{@link ChatSessionHandle#sendText(String)} — 发给 gateway</li>
 *   <li>{@link ChatSessionHandle#addTurnEndListener(java.util.function.Consumer)} — 回复结束回调</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KwsWebSocketHandler extends AbstractWebSocketHandler {

    private final KwsService kwsService;
    private final SttService sttService;          // 复用现有
    private final TtsService ttsService;          // 复用现有
    private final ChatBridgeService chatBridge;   // 复用现有
    private final VadService vadService;          // v3-vad B 方案: 后端 VAD 自动切分录音
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String ATTR_CHAT = "chat";
    private static final String ATTR_PCM_BUFFER = "pcmBuffer";
    private static final String ATTR_MODE = "mode";   // "kws" | "recording"
    private static final String ATTR_VAD_FRAME_COUNT = "_vadFrameCount";

    /**
     * 每多少个 binary 帧 (100ms/帧) 做一次 VAD 检测。
     * 500ms 一次足够灵敏,也不会过度消耗 CPU (silero-vad v4 一帧 ~1ms)。
     */
    private static final int VAD_CHECK_INTERVAL = 5;

    /**
     * 检测到说话结束后,需要持续多久的静音才确认"说完了"。
     * 默认 800ms (silero-vad minSilenceDuration 是 500ms,加 300ms buffer 防误判)。
     * 2026-08-01 调到 400ms (silero-vad 默认 min_silence 加薄 buffer), 加快录音结束响应
     */
    private static final float VAD_SILENCE_CONFIRM_MS = 400f;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("KWS WS connected: {}", session.getId());

        // 复用现有 ChatBridgeService 开 chat session
        ChatSessionHandle chat = chatBridge.open(session);
        session.getAttributes().put(ATTR_CHAT, chat);

        // 注册 turn.end 回调 -> 调现有 TtsService
        chat.addTurnEndListener(fullText -> {
            try {
                if (fullText == null || fullText.isBlank()) return;
                log.info("🔊 KWS turn.done -> TTS ({} chars)", fullText.length());
                byte[] audio = ttsService.synthesize(fullText);  // 调现有方法
                sendToBrowser(session, Map.of(
                        "type", "assistant.audio",
                        "audio", Base64.getEncoder().encodeToString(audio),
                        "format", "mp3"
                ));
            } catch (Exception e) {
                log.warn("KWS TTS 失败: {}", e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String mode = (String) session.getAttributes().get(ATTR_MODE);
        // 修: ByteBuffer.array() 返回 backing array,可能大于实际数据 (限是 limit())
        // 改用 remaining() 拿到真实长度,避免读到垃圾 0
        java.nio.ByteBuffer kwsBuf = message.getPayload();
        byte[] payload = new byte[kwsBuf.remaining()];
        kwsBuf.get(payload);

        if ("kws".equals(mode)) {
            // KWS 模式:每帧过 KwsService
            // 诊断: 每 200 帧打一次原始字节,看后端实际收到的内容
            Integer dbgCount = (Integer) session.getAttributes().computeIfAbsent("_dbgKwsRaw", k -> 0);
            dbgCount++;
            session.getAttributes().put("_dbgKwsRaw", dbgCount);
            if (dbgCount % 200 == 0 && payload.length >= 8) {
                int[] head = new int[4];
                for (int j = 0; j < 4; j++) {
                    int lo = payload[2 * j] & 0xff;
                    int hi = payload[2 * j + 1] & 0xff;
                    short s = (short) ((hi << 8) | lo);
                    head[j] = s;
                }
                log.info("🔬 KWS raw payload.len={} head4samples={}",
                        payload.length, java.util.Arrays.toString(head));
            }
            String keyword = kwsService.acceptFrame(session.getId(), payload);
            if (!keyword.isEmpty()) {
                log.info("🔥 Wake detected: session={}, keyword={}", session.getId(), keyword);
                // 命中 -> 停 KWS,发 wake.detected,等前端切到录音模式
                kwsService.stopSession(session.getId());
                session.getAttributes().remove(ATTR_MODE);
                sendToBrowser(session, Map.of(
                        "type", "wake.detected",
                        "keyword", keyword,
                        "timestamp", System.currentTimeMillis()
                ));
            }
        } else {
            // 录音模式或其他:累积到 buffer + B 方案 VAD 后端切分
            ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
            if (buf == null) {
                log.warn("⚠️ KWS Handler 收到 binary 但无 audio.start,丢弃 {} bytes", payload.length);
                return;
            }
            try {
                buf.write(payload);
            } catch (IOException e) {
                log.warn("写 PCM buffer 失败", e);
                return;
            }

            // B 方案: 后端 VAD 每 N 帧检测一次，识别到说话结束 + 静音就自动 audio.end
            if (vadService.isEnabled()) {
                Integer frameCount = (Integer) session.getAttributes().computeIfAbsent(ATTR_VAD_FRAME_COUNT, k -> 0);
                frameCount++;
                session.getAttributes().put(ATTR_VAD_FRAME_COUNT, frameCount);
                if (frameCount % VAD_CHECK_INTERVAL == 0) {
                    checkVadAndAutoTrigger(session, buf);
                }
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg;
        try {
            msg = mapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            log.warn("无法解析 KWS 消息: {}", e.getMessage());
            return;
        }

        String type = (String) msg.get("type");
        log.info("📥 KWS msg: type={}", type);

        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);

        if ("audio.kws.start".equals(type)) {
            // 进入 KWS 监听模式
            kwsService.startSession(session.getId());
            session.getAttributes().put(ATTR_MODE, "kws");
            sendToBrowser(session, Map.of(
                    "type", "kws.ack",
                    "state", "listening",
                    "keywords", kwsService.keywords()
            ));

        } else if ("audio.kws.stop".equals(type)) {
            kwsService.stopSession(session.getId());
            session.getAttributes().remove(ATTR_MODE);

        } else if ("audio.start".equals(type)) {
            // 切到录音模式 (唤醒后前端发这个)
            session.getAttributes().put(ATTR_MODE, "recording");
            session.getAttributes().put(ATTR_PCM_BUFFER, new ByteArrayOutputStream());
            int sampleRate = ((Number) msg.getOrDefault("sampleRate", 16000)).intValue();
            String encoding = (String) msg.getOrDefault("encoding", "pcm_s16le");
            log.info("🎤 KWS audio.start: sampleRate={}, encoding={}", sampleRate, encoding);
            sendToBrowser(session, Map.of("type", "audio.ack", "state", "recording"));

        } else if ("audio.end".equals(type)) {
            // 手动 audio.end -> 复用 VAD 自动触发的同一个 trigger
            triggerSttAndChat(session, "manual");

        } else if ("audio.cancel".equals(type)) {
            session.getAttributes().remove(ATTR_PCM_BUFFER);
            session.getAttributes().remove(ATTR_MODE);
            log.info("🎤 KWS audio.cancel");

        } else if ("ping".equals(type)) {
            try {
                String json = mapper.writeValueAsString(Map.of("type", "pong"));
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.warn("KWS send pong 失败", e);
            }

        } else {
            log.debug("KWS 未知 msg type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("KWS WS closed: {} ({})", session.getId(), status);
        kwsService.stopSession(session.getId());
        ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);
        if (chat != null) chat.close();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("KWS WS transport error", exception);
    }

    private void sendToBrowser(WebSocketSession session, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(new LinkedHashMap<>(data));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("KWS sendToBrowser 失败: {}", e.getMessage());
        }
    }

    /**
     * STT + Chat 触发的公共逻辑。
     * <p>被两处调用:
     * <ul>
     *   <li>手动 {@code audio.end} 文本命令 (source="manual")</li>
     *   <li>VAD B 方案检测到说话结束 + 静音阈值 (source="vad")</li>
     * </ul>
     * <p>行为: 取累积的 PCM buffer → 调 SttService.recognize → 发 user.text → 发 chat.sendText。
     * TTS 在 turn.done 回调里 (在 {@link #afterConnectionEstablished} 里注册)。
     *
     * @param source 触发来源, 仅用于日志 ("manual" | "vad")
     */
    private void triggerSttAndChat(WebSocketSession session, String source) {
        ByteArrayOutputStream buf = (ByteArrayOutputStream) session.getAttributes().get(ATTR_PCM_BUFFER);
        if (buf == null || buf.size() == 0) {
            if ("manual".equals(source)) {
                // 手动 audio.end 但 buffer 空 → 可能是前端超时后发的, 发错误提示
                sendToBrowser(session, Map.of("type", "error", "message", "audio buffer empty"));
            } else {
                // VAD 触发但 buffer 空 → 静默静音刚起始被误判, 忽略
                log.debug("🎙️ VAD auto-trigger skipped ({}): empty buffer", source);
            }
            return;
        }
        byte[] pcm = buf.toByteArray();
        session.getAttributes().remove(ATTR_PCM_BUFFER);
        session.getAttributes().remove(ATTR_MODE);
        session.getAttributes().remove(ATTR_VAD_FRAME_COUNT);
        log.info("🎤 KWS audio.end (source={}): {} bytes PCM ({}ms @16kHz)",
                source, pcm.length, pcm.length / 32);
        try {
            String text = sttService.recognize(pcm);
            sendToBrowser(session, Map.of(
                    "type", "user.text",
                    "text", text,
                    "isFinal", true
            ));
            ChatSessionHandle chat = (ChatSessionHandle) session.getAttributes().get(ATTR_CHAT);
            if (chat != null && !text.isBlank()) {
                log.info("📤 KWS STT -> chat.sendText: \"{}\"", text);
                chat.sendText(text);
            } else {
                log.warn("⚠️ KWS STT 没识别出文字 (空文本),不发 chat");
            }
        } catch (SttService.SttException e) {
            log.warn("KWS STT 失败: {}", e.getMessage());
            sendToBrowser(session, Map.of("type", "error", "message", "STT failed: " + e.getMessage()));
        }
    }

    /**
     * B 方案: 后端 VAD 检测。
     * <p>每隔 {@link #VAD_CHECK_INTERVAL} 个 binary 帧调一次。
     * 调用 {@link VadService#split(float[])} 拿 segments,
     * 如果最后一段后面跟了 {@link #VAD_SILENCE_CONFIRM_MS} 以上的静音 → 触发 audio.end 流程。
     */
    private void checkVadAndAutoTrigger(WebSocketSession session, ByteArrayOutputStream buf) {
        byte[] pcm = buf.toByteArray();
        float[] samples = pcmFloat32BytesToFloat(pcm);
        if (samples.length == 0) return;

        List<VadService.VadSegment> segments = vadService.split(samples);
        if (segments.isEmpty()) {
            log.debug("🎙️ VAD: no segments yet (纯静音 / 语音未达 min_speech_duration)");
            return;
        }

        VadService.VadSegment last = segments.get(segments.size() - 1);
        int trailingSilenceSamples = samples.length - last.endSample();
        float trailingSilenceMs = trailingSilenceSamples * 1000f / 16000f;

        log.info("🎙️ VAD: {} segments, last.end={}/{}, trailing={}ms (need ≥{}ms)",
                segments.size(), last.endSample(), samples.length,
                String.format("%.0f", trailingSilenceMs),
                (int) VAD_SILENCE_CONFIRM_MS);

        if (trailingSilenceMs >= VAD_SILENCE_CONFIRM_MS) {
            log.info("🎙️ VAD auto-trigger audio.end (trailing silence {}ms ≥ {}ms)",
                    String.format("%.0f", trailingSilenceMs),
                    (int) VAD_SILENCE_CONFIRM_MS);
            triggerSttAndChat(session, "vad");
        }
    }

    /**
     * Float32 LE bytes (4 字节/样本) → float[] 数组。
     * <p>跟 {@link KwsService#acceptFrame} 同样的格式 (前端 pcm-worklet-kws.js 输出 Float32Array.buffer)。
     */
    private static float[] pcmFloat32BytesToFloat(byte[] pcm) {
        int n = pcm.length / 4;
        if (n == 0) return new float[0];
        float[] out = new float[n];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }
}