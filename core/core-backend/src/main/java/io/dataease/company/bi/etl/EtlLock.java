package io.dataease.company.bi.etl;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EtlLock implements AutoCloseable {
    private final EtlConnections.Handle handle;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"company-etl-lock");t.setDaemon(true);return t; });
    private volatile Statement active;
    private boolean acquired;
    public EtlLock(EtlConnections.Handle handle) throws Exception {
        this.handle = handle;
        try (Statement s = handle.connection().createStatement()) {
            s.setQueryTimeout(5);
            try (ResultSet r = s.executeQuery("SELECT GET_LOCK('companybi_etl_refresh',0)")) { acquired = r.next() && r.getInt(1) == 1; }
        } catch (Exception e) { handle.close();watch.shutdownNow();throw e; }
        if (acquired) watch.scheduleWithFixedDelay(() -> {
            try { check(); } catch (Exception e) {
                lost.set(true);
                Statement statement = active;
                try { if (statement != null) statement.cancel(); } catch (SQLException ignored) { }
            }
        },2,2,TimeUnit.SECONDS);
    }
    public boolean acquired() { return acquired; }
    public void active(Statement statement) { active = statement; }
    public synchronized void check() {
        if (!acquired || lost.get()) throw new IllegalStateException("ETL 锁已丢失");
        try (Statement s = handle.connection().createStatement()) {
            s.setQueryTimeout(5);
            try (ResultSet r = s.executeQuery("SELECT IS_USED_LOCK('companybi_etl_refresh')=CONNECTION_ID()")) {
                if (!r.next() || !r.getBoolean(1)) throw new SQLException("Lock lost");
            }
        } catch (Exception e) { lost.set(true);throw new IllegalStateException("ETL 锁已丢失"); }
    }
    public void close() throws Exception {
        watch.shutdownNow();
        synchronized (this) {
            try {
                if (acquired && !lost.get()) try (Statement s = handle.connection().createStatement()) {
                    s.setQueryTimeout(5);s.execute("SELECT RELEASE_LOCK('companybi_etl_refresh')");
                }
            } catch (SQLException e) {
                lost.set(true);
            } finally {
                if (lost.get()) { try { handle.close(); } catch (SQLException ignored) { /* server already closed the lock connection */ } }
                else handle.close();
            }
        }
    }
}
