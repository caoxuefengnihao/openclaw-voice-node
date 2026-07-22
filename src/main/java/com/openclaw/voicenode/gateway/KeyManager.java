package com.openclaw.voicenode.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.voicenode.config.VoiceNodeProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ed25519 设备密钥管理。
 *
 * 1:1 对应 custom_node.py 的 KeyManager。
 * 密钥持久化到 ~/.openclaw-voice-node/device.json（0o600）。
 * deviceId = SHA-256(publicKey) 的 hex。
 */
@Slf4j
@Component
public class KeyManager {

    private final VoiceNodeProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    @Getter private Ed25519PrivateKeyParameters privateKey;
    @Getter private Ed25519PublicKeyParameters publicKey;
    @Getter private String deviceId;
    @Getter private String publicKeyB64Url;
    @Getter private String deviceToken;

    public KeyManager(VoiceNodeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws Exception {
        Path stateFile = Path.of(props.device().stateFile());

        if (Files.exists(stateFile)) {
            @SuppressWarnings("unchecked")
            Map<String, String> data = mapper.readValue(stateFile.toFile(), Map.class);
            byte[] privBytes = Base64.getUrlDecoder().decode(data.get("sk_b64url"));
            byte[] pubBytes = Base64.getUrlDecoder().decode(data.get("pk_b64url"));
            this.privateKey = new Ed25519PrivateKeyParameters(privBytes, 0);
            this.publicKey = new Ed25519PublicKeyParameters(pubBytes, 0);
            this.deviceId = data.get("deviceId");
            this.publicKeyB64Url = data.get("pk_b64url");
            log.info("加载已有设备密钥 deviceId={}", deviceId.substring(0, 16) + "...");

            // 尝试加载 deviceToken
            Path tokenFile = stateFile.getParent().resolve("token.json");
            if (Files.exists(tokenFile)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> tokData = mapper.readValue(tokenFile.toFile(), Map.class);
                    this.deviceToken = tokData.get("deviceToken");
                    log.info("加载已配对 deviceToken");
                } catch (Exception e) {
                    log.warn("读 deviceToken 失败: {}", e.getMessage());
                }
            }
        } else {
            Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
            gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
            var pair = gen.generateKeyPair();
            this.privateKey = (Ed25519PrivateKeyParameters) pair.getPrivate();
            this.publicKey = (Ed25519PublicKeyParameters) pair.getPublic();
            this.deviceId = sha256Hex(publicKey.getEncoded());
            this.publicKeyB64Url = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(publicKey.getEncoded());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sk_b64url", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(privateKey.getEncoded()));
            data.put("pk_b64url", publicKeyB64Url);
            data.put("deviceId", deviceId);
            data.put("createdAt", System.currentTimeMillis());

            Files.createDirectories(stateFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), data);
            // POSIX 文件权限
            try {
                stateFile.toFile().setReadable(false, false);
                stateFile.toFile().setReadable(true, true);
                stateFile.toFile().setWritable(false, false);
                stateFile.toFile().setWritable(true, true);
            } catch (Exception ignore) { /* Windows 等不支持 */ }
            log.info("生成新设备密钥 deviceId={}", deviceId.substring(0, 16) + "...");
        }
    }

    /**
     * 持久化 deviceToken（首次配对后由 gateway 返回的临时凭证，后续连接用它免再次配对）
     */
    public synchronized void saveDeviceToken(String token) throws Exception {
        this.deviceToken = token;
        Path tokenFile = Path.of(props.device().stateFile()).getParent().resolve("token.json");
        Map<String, String> data = new LinkedHashMap<>();
        data.put("deviceToken", token);
        Files.createDirectories(tokenFile.getParent());
        mapper.writeValue(tokenFile.toFile(), data);
        // POSIX 文件权限
        try {
            tokenFile.toFile().setReadable(false, false);
            tokenFile.toFile().setReadable(true, true);
            tokenFile.toFile().setWritable(false, false);
            tokenFile.toFile().setWritable(true, true);
        } catch (Exception ignore) {
        }
        log.info("deviceToken 已保存到 {}", tokenFile);
    }

    /**
     * 对 payload 字符串做 Ed25519 签名，返回 base64url（无 padding）
     */
    public String sign(String payload) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        signer.update(data, 0, data.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature());
    }

    /**
     * 构造 OpenClaw 设备认证 v2 签名 payload（与 custom_node.py build_v2_payload 对齐）
     */
    public String buildAuthPayload(long signedAtMs, String token, String nonce) {
        return String.join("|",
                "v2",
                deviceId,
                props.gateway().clientId(),
                props.gateway().clientMode(),
                props.gateway().role(),
                props.gateway().scopes(),
                String.valueOf(signedAtMs),
                token == null ? "" : token,
                nonce
        );
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
