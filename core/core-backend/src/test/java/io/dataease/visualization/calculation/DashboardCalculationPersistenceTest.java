package io.dataease.visualization.calculation;

import io.dataease.api.visualization.vo.DataVisualizationVO;
import io.dataease.utils.JsonUtil;
import io.dataease.visualization.dao.auto.entity.DataVisualizationInfo;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real migration and read/snapshot/publish XML against an isolated database. */
class DashboardCalculationPersistenceTest {
    @Test void migrationReadReopenAndPublishKeepDashboardDefinitionsIsolated() throws Exception {
        Path resources = Path.of("src/main/resources");
        var dataSource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:dashboardcalc;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            List<String> columns = new ArrayList<>();
            for (var field : DataVisualizationInfo.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("calculationConfig")) continue;
                String name = field.getName().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
                String type = field.getType() == String.class || name.equals("id") || name.equals("pid") ? "VARCHAR" : "BIGINT";
                columns.add("`" + name + "` " + type);
            }
            for (String table : List.of("data_visualization_info", "snapshot_data_visualization_info"))
                statement.execute("CREATE TABLE " + table + " (" + String.join(",", columns) + ")");
            for (String sql : Files.readString(resources.resolve("db/migration/V2.10.26__dashboard_calculation.sql")).split(";"))
                if (!sql.isBlank()) statement.execute(sql);
            String config = (String) JsonUtil.toJSONString(new DashboardCalculationValidator().validate(DashboardCalculationTest.example(), DashboardCalculationTest.publicFields()));
            try (var insert = connection.prepareStatement("INSERT INTO data_visualization_info (id,name,type,delete_flag,calculation_config) VALUES (?,?,'dashboard',0,?)")) {
                insert.setString(1, "100"); insert.setString(2, "A"); insert.setString(3, config); insert.executeUpdate();
                insert.setString(1, "200"); insert.setString(2, "B"); insert.setString(3, null); insert.executeUpdate();
            }
            Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            String xml = Files.readString(resources.resolve("mybatis/ExtDataVisualizationMapper.xml"));
            StringBuilder statements = new StringBuilder();
            for (String id : List.of("findDvInfo", "snapshotDataV", "restoreDataV")) {
                var matcher = Pattern.compile("<(select|insert) id=\"" + id + "\"[^>]*>.*?</\\1>", Pattern.DOTALL).matcher(xml);
                assertTrue(matcher.find(), id + " must exist in production mapper"); statements.append(matcher.group());
            }
            String mapper = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\"><mapper namespace=\"phase1\">" + statements + "</mapper>";
            new XMLMapperBuilder(new ByteArrayInputStream(mapper.getBytes(StandardCharsets.UTF_8)), configuration, "phase1.xml", configuration.getSqlFragments()).parse();
            var factory = new SqlSessionFactoryBuilder().build(configuration);
            try (var session = factory.openSession(true)) {
                session.insert("phase1.snapshotDataV", Map.of("dvId", 100L));
                session.insert("phase1.snapshotDataV", Map.of("dvId", 200L));
            }
            var draft = DashboardCalculationManage.parse(config); draft.getDatasetFields().get(0).setName("YTD期间新版本");
            String draftJson = (String) JsonUtil.toJSONString(draft);
            try (var update = connection.prepareStatement("UPDATE snapshot_data_visualization_info SET calculation_config=? WHERE id='100'")) {
                update.setString(1, draftJson); update.executeUpdate();
            }
            try (var reopened = factory.openSession(true)) {
                DataVisualizationVO working = reopened.selectOne("phase1.findDvInfo", Map.of("dvId", 100L, "resourceTable", "snapshot"));
                DataVisualizationVO published = reopened.selectOne("phase1.findDvInfo", Map.of("dvId", 100L, "resourceTable", "core"));
                assertEquals(draftJson, working.getCalculationConfig());
                assertEquals(config, published.getCalculationConfig());
                statement.execute("DELETE FROM data_visualization_info WHERE id='100'");
                reopened.insert("phase1.restoreDataV", Map.of("dvId", 100L));
            }
            try (var reopened = factory.openSession(true)) {
                DataVisualizationVO published = reopened.selectOne("phase1.findDvInfo", Map.of("dvId", 100L, "resourceTable", "core"));
                DataVisualizationVO other = reopened.selectOne("phase1.findDvInfo", Map.of("dvId", 200L, "resourceTable", "core"));
                assertEquals(draftJson, published.getCalculationConfig());
                assertNull(other.getCalculationConfig());
                assertEquals(1, DashboardCalculationManage.toFields(DashboardCalculationManage.parse(published.getCalculationConfig()), 100L, 10L).size());
                assertTrue(DashboardCalculationManage.toFields(DashboardCalculationManage.parse(other.getCalculationConfig()), 200L, 10L).isEmpty());
            }
        }
    }
}
