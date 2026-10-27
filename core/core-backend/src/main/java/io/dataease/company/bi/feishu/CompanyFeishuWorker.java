package io.dataease.company.bi.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.integration.IntegrationSecretCipher;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** A private child process, using stdin/stdout RPC. No HTTP port or independent deployment. */
@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CompanyFeishuConnectionService connections;
    private final IntegrationSecretCipher cipher;
    private final DataSource dataSource;
    private final String node, workerDir, dataDir;
    private final int port;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentMap<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "company-integration-supervisor"); t.setDaemon(true); return t;
    });
    private volatile Process process;
    private volatile String state = "STOPPED";
    private volatile boolean closing;
    private volatile boolean ready;
    private BufferedWriter input;

    public CompanyFeishuWorker(CompanyFeishuConnectionService connections, IntegrationSecretCipher cipher, DataSource dataSource,
            @Value("${company.feishu.node:node}") String node,
            @Value("${company.feishu.worker-dir:/opt/apps/integration-worker}") String workerDir,
            @Value("${company.bi.data-dir:/opt/dataease2.0/data}") String dataDir,
            @Value("${server.port:8100}") int port) {
        this.connections = connections; this.cipher = cipher; this.dataSource = dataSource;
        this.node = node; this.workerDir = workerDir; this.dataDir = dataDir; this.port = port;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startSupervisor() {
        ready = true;
        supervisor.scheduleWithFixedDelay(() -> {
            try {
                if (connections.enabled("runtimeEnabled")) ensureStarted();
                // Disabling is read by the worker before every operation/tick, without killing in-flight sends.
            } catch (Exception ignored) { if (!"MISSING_RUNTIME".equals(state)) state = "UNAVAILABLE"; }
        }, 0, 10, TimeUnit.SECONDS);
    }
    public Map<String, Object> status() {
        return Map.of("mode", "EMBEDDED", "state", state, "running", process != null && process.isAlive());
    }
    public Map<String, Object> call(String method, Map<String, Object> args) {
        if (!ready) CompanyAdminErrors.invalid("BI 正在启动，请稍后重试");
        if (!connections.enabled("runtimeEnabled")) CompanyAdminErrors.invalid("请先在集成中心配置并启用飞书连接");
        String id = String.valueOf(ids.incrementAndGet());
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        try {
            synchronized (this) {
                ensureStarted();
                pending.put(id, result);
                input.write(JSON.writeValueAsString(Map.of("id", id, "method", method, "args", args)));
                input.newLine(); input.flush();
            }
            return result.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String detail = e instanceof ExecutionException && e.getCause() != null ? e.getCause().getMessage() : null;
            CompanyAdminErrors.invalid(detail == null ? "BI 内部飞书执行器不可用或请求超时，请查看集成中心运行状态" : detail);
            return Map.of();
        } finally { pending.remove(id); }
    }
    private synchronized void ensureStarted() throws Exception {
        if (closing) throw new IllegalStateException("stopping");
        if (process != null && process.isAlive()) return;
        pending.values().forEach(f -> f.completeExceptionally(new IOException("BI 内部飞书执行器已重启，请检查原任务记录后再试")));
        pending.clear();
        Path dir = Path.of(workerDir).toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("src/embedded-worker.mjs"))) {
            state = "MISSING_RUNTIME"; throw new IOException("missing packaged integration worker");
        }
        Path logs = Path.of(dataDir, "integration", "logs"); Files.createDirectories(logs);
        ProcessBuilder builder = new ProcessBuilder(node, dir.resolve("src/embedded-worker.mjs").toString()).directory(dir.toFile());
        Map<String, String> env = builder.environment();
        // Do not inherit old local .env settings or production destinations from a developer shell.
        env.keySet().removeIf(k -> k.startsWith("FEISHU_") || k.startsWith("DE_") || k.startsWith("QIWEI_") || k.startsWith("ETL_"));
        env.put("COMPANY_BI_EMBEDDED_WORKER", "true");
        env.put("COMPANY_BI_INTEGRATION_KEY", cipher.workerKey());
        env.put("FEISHU_LOG_DIR", logs.toString());
        env.put("FEISHU_TMP_DIR", Path.of(dataDir, "integration", "tmp").toString());
        env.put("DE_BASE_URL", "http://127.0.0.1:" + port);
        env.put("DE_MYSQL_TRANSPORT", "tcp");
        try (var connection = dataSource.getConnection()) {
            URI uri = URI.create(connection.getMetaData().getURL().replaceFirst("^jdbc:", ""));
            if (!"mysql".equals(uri.getScheme()) || uri.getHost() == null) throw new IOException("metadata must use MySQL");
            env.put("DE_MYSQL_HOST", uri.getHost());
            env.put("DE_MYSQL_PORT", String.valueOf(uri.getPort() < 0 ? 3306 : uri.getPort()));
            env.put("DE_MYSQL_DATABASE", uri.getPath().substring(1));
            env.put("DE_MYSQL_USER", connection.getMetaData().getUserName().split("@")[0]);
        }
        // Obtain the exact configured password through Spring, not from JDBC diagnostics or process arguments.
        env.put("DE_MYSQL_PASSWORD", metadataPassword);
        builder.redirectError(ProcessBuilder.Redirect.appendTo(logs.resolve("worker.log").toFile()));
        state = "STARTING";
        Process child = builder.start(); process = child;
        input = child.outputWriter(StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> readResponses(child), "company-integration-rpc"); reader.setDaemon(true); reader.start();
    }
    @Value("${spring.datasource.password:}") private String metadataPassword;
    private void readResponses(Process child) {
        try (BufferedReader reader = child.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> response = JSON.readValue(line, new TypeReference<>() {});
                if (Boolean.TRUE.equals(response.get("ready"))) { state = "RUNNING"; continue; }
                var future = pending.get(String.valueOf(response.get("id")));
                if (future == null) continue;
                if (response.get("error") != null) future.completeExceptionally(new IOException(String.valueOf(response.get("error"))));
                else future.complete((Map<String, Object>) response.getOrDefault("result", Map.of()));
            }
        } catch (Exception ignored) { /* Never log RPC bodies: they may contain employee data. */ }
        finally {
            synchronized (this) {
                if (process == child) {
                    state = "UNAVAILABLE"; if (child.isAlive()) child.destroy();
                    pending.values().forEach(f -> f.completeExceptionally(new IOException("BI 内部飞书执行器已退出，请检查原任务记录后再试")));
                }
            }
        }
    }
    @PreDestroy public synchronized void close() {
        closing = true; supervisor.shutdownNow(); state = "STOPPED";
        if (process != null) {
            try {
                input.write("{\"id\":\"shutdown\",\"method\":\"shutdown\",\"args\":{}}"); input.newLine(); input.flush();
                if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (Exception ignored) { process.destroyForcibly(); }
        }
    }
}
