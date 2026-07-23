package com.openclaw.voicenode.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * .env 文件加载器 (学自 dw_auto_platform 的 DotEnvLoader.java)。
 *
 * Spring Boot EnvironmentPostProcessor — 在 application.yml 加载**之前**跑,
 * 把 .env 里的 KEY=VAL 注入到 Spring Environment,作为 HIGHEST_PRECEDENCE property source。
 *
 * 优点:
 * - 零依赖 (不需 spring-dotenv 库)
 * - 比 shell source .env 更稳 (IDE / docker / CI 通用)
 * - 占位符 fallback 到 application.yml 里的默认值
 *
 * 注册方式 (Spring Boot 3.x):
 *   META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports
 *
 * 环境变量优先级 (从低到高):
 *   application.yml 默认值  <  .env  <  系统 env  <  JVM -D 参数
 */
public class DotEnvLoader implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // 从 cwd 往上找 .env (voice-node 不是 monorepo,不需要 backend/.env 这种变体)
        File checkDir = new File(System.getProperty("user.dir", "."));
        Resource resource = null;
        String path = null;

        for (int i = 0; i < 4 && checkDir != null; i++) {
            File candidate = new File(checkDir, ".env");
            if (candidate.exists()) {
                resource = new FileSystemResource(candidate);
                path = candidate.getAbsolutePath();
                break;
            }
            checkDir = checkDir.getParentFile();
        }
        // Fallback: 走 .env 路径但让 Spring 自己处理 "不存在"
        if (resource == null) {
            File fallback = new File(System.getProperty("user.dir", "."), ".env");
            path = fallback.getAbsolutePath();
        }

        if (resource == null || !resource.exists()) {
            System.err.println("[DotEnvLoader] WARN no .env found, fallback to application.yml defaults");
            return;
        }

        Map<String, Object> props = new LinkedHashMap<>();
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                // 去引号 ("..." 或 '...')
                if (val.length() >= 2) {
                    if ((val.startsWith("\"") && val.endsWith("\"")) ||
                        (val.startsWith("'")  && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                }
                if (!key.isEmpty()) props.put(key, val);
            }
        } catch (Exception e) {
            System.err.println("[DotEnvLoader] ERROR reading " + path + ": " + e.getMessage());
            return;
        }

        env.getPropertySources().addFirst(new MapPropertySource("dotenv", props));
        System.err.println("[DotEnvLoader] OK loaded " + props.size() + " vars from " + path);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}