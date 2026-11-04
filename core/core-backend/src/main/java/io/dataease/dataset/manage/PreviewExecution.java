package io.dataease.dataset.manage;

import java.sql.Statement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Request-scoped JDBC limits. Never affects exports or chart queries. */
public final class PreviewExecution implements AutoCloseable {
    private static final ThreadLocal<PreviewExecution> CURRENT = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, PreviewExecution> RUNNING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> CANCELLED = new ConcurrentHashMap<>();
    private static final Semaphore DETAILS = new Semaphore(4);
    private static final Semaphore COUNTS = new Semaphore(1);
    private final String key;
    private final long user;
    private final Semaphore lane;
    private final long deadline;
    private final int maxRows;
    private Statement statement;
    private boolean cancelled;
    private long characters;

    private PreviewExecution(String key, long user, boolean count, int maxRows) {
        this.key = key; this.user = user; lane = count ? COUNTS : DETAILS;
        this.maxRows = count ? 1 : maxRows;
        deadline = System.nanoTime() + (count ? 5L : 15L) * 1_000_000_000L;
    }

    public static PreviewExecution open(String id, long user, boolean count, int maxRows) {
        if (maxRows < 1 || maxRows > 201) throw new IllegalArgumentException("预览每页最多200行");
        String key = user + ":" + (id == null ? UUID.randomUUID() : UUID.fromString(id));
        PreviewExecution run = new PreviewExecution(key, user, count, maxRows);
        if (!run.lane.tryAcquire()) throw new IllegalArgumentException("查询繁忙，请稍后重试");
        if (RUNNING.putIfAbsent(key, run) != null) {
            run.lane.release();
            throw new IllegalArgumentException("查询标识重复，请重新查询");
        }
        // Cancellation can arrive before the HTTP query reaches this instance.
        Long cancelledAt = CANCELLED.get(key);
        if (cancelledAt != null && cancelledAt > System.currentTimeMillis()) run.cancelled = true;
        CURRENT.set(run);
        return run;
    }

    public static void cancel(String id, long user) {
        String key = user + ":" + UUID.fromString(id);
        long now = System.currentTimeMillis();
        CANCELLED.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (CANCELLED.size() < 4096) CANCELLED.put(key, now + 60_000);
        PreviewExecution run = RUNNING.get(key);
        if (run != null && run.user == user) run.stop();
    }

    private synchronized void stop() {
        cancelled = true;
        if (statement != null) {
            try { statement.cancel(); }
            catch (SQLException e) {
                org.slf4j.LoggerFactory.getLogger(PreviewExecution.class).warn("Preview cancellation failed ({})", e.getSQLState());
            }
        }
    }

    public synchronized void check() {
        if (cancelled) throw new IllegalArgumentException("查询已取消");
        if (System.nanoTime() >= deadline) throw new IllegalArgumentException("查询超时，请缩小筛选范围或减少显示列");
    }

    public static boolean active() { return CURRENT.get() != null; }

    public static void acceptRow(String[] row) {
        PreviewExecution run = CURRENT.get();
        if (run == null) return;
        run.check();
        for (String cell : row) if (cell != null) run.characters += cell.length();
        if (run.characters > 1_000_000) throw new IllegalArgumentException("本页数据过大，请减少显示列或每页行数");
    }

    /** Bind before execute, unbind before the statement/connection is returned. */
    public static AutoCloseable bind(Statement statement) throws SQLException {
        PreviewExecution run = CURRENT.get();
        if (run == null) return () -> {};
        synchronized (run) {
            run.check();
            int remaining = (int) Math.max(1, (run.deadline - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
            int configured = statement.getQueryTimeout();
            statement.setQueryTimeout(configured > 0 ? Math.min(configured, remaining) : remaining);
            statement.setMaxRows(run.maxRows);
            run.statement = statement;
        }
        return () -> { synchronized (run) { run.statement = null; } };
    }

    @Override public void close() {
        CURRENT.remove();
        RUNNING.remove(key, this);
        lane.release();
    }
}
