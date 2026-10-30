package io.dataease.company.bi.dataupdate;

import java.sql.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared by the worker and its deadline / cancellation monitor. */
public final class UpdateExecution {
    private final Set<Statement> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile String reason = "运行已取消";
    private final long deadline;
    private java.util.function.BooleanSupplier commitAllowed = () -> true;
    public UpdateExecution(int timeoutSeconds) { deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L; }
    public void check() throws SQLException {
        if (System.nanoTime() >= deadline) stop("执行超时");
        if (stopped.get()) throw new SQLException(reason, "57014");
    }
    public <T extends Statement> T watch(T statement) throws SQLException {
        active.add(statement);
        try { check(); statement.setQueryTimeout((int)Math.max(1, (deadline - System.nanoTime()) / 1_000_000_000L)); return statement; }
        catch (SQLException e) { active.remove(statement); statement.close(); throw e; }
    }
    public void forget(Statement statement) { active.remove(statement); }
    public void stop(String message) {
        if (stopped.compareAndSet(false, true)) reason = message;
        active.forEach(statement -> { try { statement.cancel(); } catch (SQLException ignored) { } });
    }
    public boolean stopped() { return stopped.get(); }
    public void commitGuard(java.util.function.BooleanSupplier guard) { commitAllowed = guard; }
    public void beforeCommit() throws SQLException {
        if (!commitAllowed.getAsBoolean()) stop("提交前检查发现运行已取消或执行锁丢失");
        check();
    }
    public String reason() { return reason; }
}
