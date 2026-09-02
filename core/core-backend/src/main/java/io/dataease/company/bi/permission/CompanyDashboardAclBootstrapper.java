package io.dataease.company.bi.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.utils.LogUtil;
import io.dataease.visualization.dao.auto.entity.DataVisualizationInfo;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardAclBootstrapper {

    public static final String COMPAT_SEED_KEY = "dashboard_admin_visitor";

    private final DataVisualizationInfoMapper visualizationInfoMapper;
    private final CompanyAclRepository aclRepository;
    private final DataSource dataSource;

    public CompanyDashboardAclBootstrapper(DataVisualizationInfoMapper visualizationInfoMapper,
                                           CompanyAclRepository aclRepository,
                                           DataSource dataSource) {
        this.visualizationInfoMapper = visualizationInfoMapper;
        this.aclRepository = aclRepository;
        this.dataSource = dataSource;
    }

    public List<Long> listExistingDashboardLeafIds() {
        QueryWrapper<DataVisualizationInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("delete_flag", false)
                .eq("node_type", "leaf")
                .eq("type", "dashboard")
                .select("id");
        List<DataVisualizationInfo> rows = visualizationInfoMapper.selectList(wrapper);
        List<Long> ids = new ArrayList<>();
        if (rows == null) {
            return ids;
        }
        for (DataVisualizationInfo row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    public int seedBootstrapAdminExistingDashboards() {
        if (alreadySeeded()) {
            LogUtil.info("Skip dashboard ACL compat seed: already completed");
            return 0;
        }
        List<Long> dashboardIds = listExistingDashboardLeafIds();
        int inserted = seedUserDashboardVisitor(CompanyBiUserService.BOOTSTRAP_ADMIN_ID, dashboardIds);
        markSeeded(inserted, dashboardIds.size());
        LogUtil.info("Seeded bootstrap admin Dashboard VISITOR ACL: inserted=" + inserted
                + " existingDashboardLeaves=" + dashboardIds.size());
        return inserted;
    }

    public int seedUserDashboardVisitor(Long userId, List<Long> dashboardIds) {
        if (userId == null || dashboardIds == null || dashboardIds.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (Long dashboardId : dashboardIds) {
            if (aclRepository.insertIfAbsentUserDashboardVisitor(userId, dashboardId, userId)) {
                inserted++;
            }
        }
        return inserted;
    }

    public boolean grantCreatorVisitor(Long userId, Long dashboardId) {
        if (userId == null || dashboardId == null) {
            return false;
        }
        return aclRepository.insertIfAbsentUserDashboardVisitor(userId, dashboardId, userId);
    }

    private boolean alreadySeeded() {
        String sql = "SELECT 1 FROM company_bi_compat_seed WHERE seed_key = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, COMPAT_SEED_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read dashboard ACL compat seed latch", e);
        }
    }

    private void markSeeded(int inserted, int planned) {
        String sql = "INSERT INTO company_bi_compat_seed (seed_key, seeded_at, inserted_count, planned_count) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, COMPAT_SEED_KEY);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, inserted);
            ps.setInt(4, planned);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write dashboard ACL compat seed latch", e);
        }
    }
}
