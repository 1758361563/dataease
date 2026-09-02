package io.dataease.company.bi.permission;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.model.BusiNodeVO;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardTreeAdapter {

    private final PermissionDecisionService permissionDecisionService;
    private final DashboardPermissionProjectionService projectionService;
    private final CompanyBiUserService userService;

    public CompanyDashboardTreeAdapter(PermissionDecisionService permissionDecisionService,
                                       DashboardPermissionProjectionService projectionService,
                                       CompanyBiUserService userService) {
        this.permissionDecisionService = permissionDecisionService;
        this.projectionService = projectionService;
        this.userService = userService;
    }

    public List<BusiNodeVO> filterAndProject(List<BusiNodeVO> tree) {
        TokenUserBO tokenUser = AuthUtils.getUser();
        if (tokenUser == null || tokenUser.getUserId() == null) {
            return List.of();
        }
        Long userId = tokenUser.getUserId();
        Set<Long> accessible = permissionDecisionService.accessibleDashboardIds(userId);
        Set<Long> folders = permissionDecisionService.accessibleFolderIds(userId);
        CompanyAccountRole role = roleOf(userId);
        DashboardPermissionProjection leaf = projectionService.project(role, true);
        return CompanyDashboardTreePruner.prune(
                tree,
                accessible,
                folders,
                leaf,
                projectionService.authorizedFolder(role),
                projectionService.folderShell());
    }

    private CompanyAccountRole roleOf(Long userId) {
        CompanyBiUser user = userService.findById(userId);
        if (user == null || user.getAccountRole() == null) {
            return null;
        }
        try {
            return CompanyAccountRole.valueOf(user.getAccountRole());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
