package io.dataease.company.bi.dataupdate;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Session-bound metadata lock shared by every BI instance using this metadata database. */
final class UpdateLock implements AutoCloseable {
    private final Connection connection;
    private final List<String> names;
    private UpdateLock(Connection connection, List<String> names) { this.connection = connection; this.names = names; }
    static UpdateLock acquire(DataSource source, String key) throws SQLException {
        return acquireMany(source,List.of(key));
    }
    static UpdateLock acquireMany(DataSource source,List<String> keys) throws SQLException {
        Connection connection = source.getConnection();
        List<String> acquired=new ArrayList<>();
        try {
        for(String key:new TreeSet<>(keys)) {
        String name = "data_update_" + UpdateStore.hash(key).substring(0,48);
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            ps.setQueryTimeout(5); ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 1) {acquired.add(name);continue;}
            }
            new UpdateLock(connection,acquired).close();return null;
        }
        }
        return new UpdateLock(connection,acquired);
        } catch (SQLException e) {new UpdateLock(connection,acquired).close();throw e;}
    }
    synchronized boolean alive() {
        try {
        for(String name:names) try (PreparedStatement ps = connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
            ps.setQueryTimeout(5); ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { if(!rs.next() || !rs.getBoolean(1)) return false; }
        }
        return true;
        } catch(SQLException e) {return false;}
    }
    @Override public synchronized void close() throws SQLException {
        try {
            for(String name:names) try(PreparedStatement ps=connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                ps.setQueryTimeout(5);ps.setString(1,name);ps.execute();
            } catch(SQLException ignored) { }
        } finally {connection.close();}
    }
}
