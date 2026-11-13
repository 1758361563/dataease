package io.dataease.company.bi.feishu;
import io.dataease.company.bi.permission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;

public class FeishuSubscriptionContentTest {
    public static void main(String[] args) throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:subscription_content;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE data_visualization_info(id BIGINT,name VARCHAR(100),node_type VARCHAR(20),type VARCHAR(20),delete_flag INT,component_data TEXT)");
        jdbc.execute("CREATE TABLE core_chart_view(id BIGINT,title VARCHAR(100),scene_id BIGINT,table_id BIGINT)");
        jdbc.execute(Files.readString(Path.of("core/core-backend/src/main/resources/db/company/company_feishu_subscription.sql")));
        var permissions = new PermissionDecisionService(null, null) {
            public boolean canViewDashboard(Long user, Long dashboard) { return user == 7 && dashboard == 1; }
            public boolean canExportDashboard(Long user, Long dashboard, CompanyExportKind kind) { return user == 7 && dashboard == 1; }
        };
        var guard = new CompanyDashboardGuard(permissions) {
            public Long currentUserId() { return 7L; }
            public void requireView(Long id) { if (id != 1) throw new IllegalArgumentException("denied"); }
            public void requireExport(Long id, CompanyExportKind kind) { requireView(id); }
        };
        var service = new FeishuSubscriptionContent(jdbc, guard, permissions);
        jdbc.update("INSERT INTO data_visualization_info VALUES(1,'Daily','leaf','dashboard',0,?), (2,'Folder','folder','dashboard',0,'[]'), (3,'Other','leaf','dashboard',0,'[]')", "[{\"children\":[{\"component\":\"VQuery\",\"propValue\":[{\"id\":\"date\",\"name\":\"Date\",\"displayType\":\"7\"}]}]}]");
        jdbc.update("INSERT INTO core_chart_view VALUES(10,'Summary',1,100),(11,'Category',1,100),(12,'Foreign',3,100)");
        check(service.dashboards().size() == 1, "only allowed dashboard leaves");
        var options = service.options(1L);
        check(((List<?>) options.get("filters")).size() == 1, "nested query discovery");
        check(((List<?>) options.get("cards")).size() == 2, "card ownership");
        var content = service.normalize("1", Map.of("mode","CARDS","chartIds",List.of("11","10")), null);
        check(content.get("chartIds").equals(List.of("11","10")), "ordered selection");
        reject(() -> service.normalize("1", Map.of("mode","CARDS","chartIds",List.of("12")), null));
        reject(() -> service.normalize("1", Map.of("mode","CARDS","chartIds",List.of("10","10")), null));
        reject(() -> service.normalize("3", Map.of("mode","DASHBOARD"), null));
        check(service.normalize("1",Map.of("mode","DASHBOARD"),null).get("chartIds").equals(List.of()),"dashboard without chart");
        var store = new CompanyFeishuJdbcStore(jdbc);
        var row = new CompanyFeishuRepository.Subscription();
        row.id=1L; row.name="Daily"; row.dashboardId="1"; row.chartId=""; row.contentJson="{\"mode\":\"DASHBOARD\"}";
        row.status="ENABLED";row.createdAt=row.updatedAt=1L;row.createdBy=row.updatedBy=7L;
        store.insertSubscription(row);
        check(store.findSubscription(1L).contentJson.equals(row.contentJson),"content persistence");
        service.requireExecution(1L);
        row.updatedBy=8L;store.updateSubscription(row);
        reject(() -> service.requireExecution(1L));
        System.out.println("Subscription content: 10 checks passed");
    }
    static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    static void reject(Runnable action) { try { action.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("expected rejection"); }
}
