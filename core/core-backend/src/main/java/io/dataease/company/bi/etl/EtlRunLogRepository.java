package io.dataease.company.bi.etl;

import java.sql.*;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.etl.EtlModel.*;

public class EtlRunLogRepository {
    public static final String TABLE = "`qiwei_new_bi`.`etl_run_log`";
    private final Connection connection;
    private final Clock clock;
    public EtlRunLogRepository(Connection connection, Clock clock) { this.connection = connection; this.clock = clock; }
    public record Row(long id, String status, String triggerType, String windowStart, String windowEnd) {}
    public Row find(String name, String kind, String planned) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT id,status,trigger_type,DATE_FORMAT(window_start,'%Y-%m-%d'),DATE_FORMAT(window_end,'%Y-%m-%d') FROM " + TABLE + " WHERE etl_name=? AND kind=? AND planned_at=?")) {
            p.setString(1,name);p.setString(2,kind);p.setString(3,planned);
            try (ResultSet r = p.executeQuery()) { return r.next() ? new Row(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)) : null; }
        }
    }
    public record Claim(Row row, boolean created) {}
    public Claim claim(String name, EtlStore.Batch batch) throws SQLException {
        Row existing = find(name,batch.kind(),batch.plannedAt());
        if (existing != null) return new Claim(existing,false);
        boolean created = false;
        try (PreparedStatement p = connection.prepareStatement("INSERT INTO " + TABLE + " (etl_name,kind,status,started_at,window_start,window_end,planned_at,trigger_type) VALUES (?,?,'running',?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1,name);p.setString(2,batch.kind());p.setString(3,time(clock.instant()));p.setString(4,batch.windowStart());
            p.setString(5,batch.windowEnd());p.setString(6,batch.plannedAt());p.setString(7,batch.triggerType());p.executeUpdate();connection.commit();created=true;
        } catch (SQLException e) {
            connection.rollback();
            if (e.getErrorCode() != 1062) throw e;
        }
        Row row = find(name,batch.kind(),batch.plannedAt());
        if (row == null) throw new SQLException("Claim missing");
        return new Claim(row,created);
    }
    public void finish(long id, String status, Long count, String note) throws SQLException {
        require(Set.of("success","fail","blocked").contains(status), "结束状态无效");
        try (PreparedStatement p = connection.prepareStatement("UPDATE " + TABLE + " SET status=?,finished_at=?,row_count=?,note=? WHERE id=? AND status='running'")) {
            p.setString(1,status);p.setString(2,time(clock.instant()));p.setObject(3,count);p.setString(4,note.substring(0,Math.min(512,note.length())));p.setLong(5,id);
            if (p.executeUpdate() != 1) throw new SQLException("Run state changed");
            connection.commit();
        }
    }
    public Long manualUpstream(String name, String kind, String end) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT id,status FROM " + TABLE + " WHERE etl_name=? AND kind=? AND window_end=? ORDER BY id DESC LIMIT 1")) {
            p.setString(1,name);p.setString(2,kind);p.setString(3,end);
            try (ResultSet r = p.executeQuery()) { return r.next() && "success".equals(r.getString(2)) ? r.getLong(1) : null; }
        }
    }
    public List<Map<String,Object>> list(String kind, String name, String from, String to, int limit) throws SQLException {
        String sql = "SELECT id,etl_name,kind,status,DATE_FORMAT(started_at,'%Y-%m-%d %H:%i:%s') started_at,DATE_FORMAT(finished_at,'%Y-%m-%d %H:%i:%s') finished_at,DATE_FORMAT(planned_at,'%Y-%m-%d %H:%i:%s') planned_at,DATE_FORMAT(window_start,'%Y-%m-%d') window_start,DATE_FORMAT(window_end,'%Y-%m-%d') window_end,trigger_type,row_count,note FROM " + TABLE + " WHERE kind=? AND (?='' OR etl_name=?) AND started_at>=? AND started_at<? ORDER BY planned_at DESC,id DESC LIMIT ?";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1,kind);p.setString(2,name);p.setString(3,name);p.setString(4,from);p.setString(5,to);p.setInt(6,limit);
            List<Map<String,Object>> rows = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i=1;i<=r.getMetaData().getColumnCount();i++) row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));
                    rows.add(row);
                }
            }
            return rows;
        }
    }
    public void interruptManual() throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("UPDATE " + TABLE + " SET status='fail',finished_at=?,note='进程重启中断' WHERE status='running' AND trigger_type='manual' AND kind IN ('refresh','refresh_test') AND etl_name IN (?,?,?)")) {
            p.setString(1,time(clock.instant()));
            for (int i=0;i<3;i++) p.setString(i+2,EtlPackageValidator.NAMES.get(i));
            p.executeUpdate();connection.commit();
        }
    }
    public List<String> resumable() throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT DISTINCT DATE_FORMAT(planned_at,'%Y-%m-%d %H:%i:%s') FROM " + TABLE + " WHERE status='running' AND trigger_type='schedule' AND kind='refresh' AND started_at>=? AND etl_name IN (?,?,?) ORDER BY 1")) {
            p.setString(1,time(clock.instant().minus(Duration.ofHours(36))));
            for (int i=0;i<3;i++) p.setString(i+2,EtlPackageValidator.NAMES.get(i));
            List<String> planned = new ArrayList<>();try (ResultSet r = p.executeQuery()) { while(r.next()) planned.add(r.getString(1)); }return planned;
        }
    }
}
