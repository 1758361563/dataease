package io.dataease.visualization.manage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.dataease.api.visualization.request.VisualizationStoreRequest;
import io.dataease.api.visualization.request.VisualizationWorkbranchQueryRequest;
import io.dataease.api.visualization.vo.VisualizationStoreVO;
import io.dataease.constant.BusiResourceEnum;
import io.dataease.exception.DEException;
import io.dataease.license.config.XpackInteract;
import io.dataease.utils.AuthUtils;
import io.dataease.utils.CommonBeanFactory;
import io.dataease.utils.CommunityUtils;
import io.dataease.utils.IDUtils;
import io.dataease.visualization.dao.auto.entity.CoreStore;
import io.dataease.visualization.dao.auto.mapper.CoreStoreMapper;
import io.dataease.visualization.dao.ext.mapper.CoreStoreExtMapper;
import io.dataease.visualization.dao.ext.po.StorePO;
import io.dataease.company.bi.permission.DashboardPermissionProjection;
import io.dataease.company.bi.permission.DashboardPermissionProjectionService;
import io.dataease.company.bi.permission.PermissionDecisionService;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VisualizationStoreManage {

    @Resource
    private CoreStoreMapper coreStoreMapper;

    @Resource
    private CoreStoreExtMapper coreStoreExtMapper;

    @Autowired
    private ObjectProvider<PermissionDecisionService> permissionDecisionService;

    @Autowired
    private ObjectProvider<DashboardPermissionProjectionService> dashboardProjectionService;

    @Autowired
    private ObjectProvider<CompanyBiUserService> companyBiUserService;

    public void execute(VisualizationStoreRequest request) {
        Long resourceId = request.getId();
        PermissionDecisionService decision = permissionDecisionService.getIfAvailable();
        if (decision != null && AuthUtils.getUser() != null) {
            if (!decision.canViewDashboard(AuthUtils.getUser().getUserId(), resourceId)) {
                io.dataease.company.bi.permission.CompanyDashboardGuard.deny();
            }
        }
        Long uid = AuthUtils.getUser().getUserId();
        if (favorited(resourceId)) {
            QueryWrapper<CoreStore> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("resource_id", resourceId);
            queryWrapper.eq("uid", uid);
            coreStoreMapper.delete(queryWrapper);
            return;
        }
        String type = request.getType();
        BusiResourceEnum busiResourceEnum = BusiResourceEnum.valueOf(type.toUpperCase());
        if (ObjectUtils.isEmpty(busiResourceEnum)) {
            DEException.throwException("type is invalid");
        }
        CoreStore coreStore = new CoreStore();
        coreStore.setId(IDUtils.snowID());
        coreStore.setTime(System.currentTimeMillis());
        coreStore.setUid(uid);
        coreStore.setResourceId(resourceId);
        coreStore.setResourceType(busiResourceEnum.getFlag());
        coreStoreMapper.insert(coreStore);
    }

    public Boolean favorited(Long resourceId) {
        QueryWrapper<CoreStore> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("resource_id", resourceId);
        queryWrapper.eq("uid", AuthUtils.getUser().getUserId());
        return coreStoreMapper.exists(queryWrapper);
    }

    @XpackInteract(value = "perFilterManage", recursion = true, invalid = true)
    public IPage<VisualizationStoreVO> query(int pageNum, int pageSize, VisualizationWorkbranchQueryRequest request) {
        IPage<StorePO> storePOIPage = proxy().queryStorePage(pageNum, pageSize, request);
        if (ObjectUtils.isEmpty(storePOIPage)) return null;
        List<VisualizationStoreVO> vos = proxy().formatResult(storePOIPage.getRecords());
        IPage<VisualizationStoreVO> ipage = new Page<>();
        ipage.setCurrent(storePOIPage.getCurrent());
        ipage.setPages(storePOIPage.getPages());
        ipage.setSize(storePOIPage.getSize());
        ipage.setTotal(storePOIPage.getTotal());
        ipage.setRecords(vos);
        return ipage;
    }

    public VisualizationStoreManage proxy() {
        return CommonBeanFactory.getBean(this.getClass());
    }

    public List<VisualizationStoreVO> formatResult(List<StorePO> pos) {
        if (CollectionUtils.isEmpty(pos)) return new ArrayList<>();
        return pos.stream().map(po -> {
            int weight = 9;
            DashboardPermissionProjectionService projectionService = dashboardProjectionService.getIfAvailable();
            CompanyBiUserService userService = companyBiUserService.getIfAvailable();
            if (projectionService != null && userService != null && AuthUtils.getUser() != null) {
                CompanyBiUser user = userService.findById(AuthUtils.getUser().getUserId());
                if (user != null && user.getAccountRole() != null) {
                    try {
                        DashboardPermissionProjection projection = projectionService.project(
                                CompanyAccountRole.valueOf(user.getAccountRole()), true);
                        weight = projection.weight();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return new VisualizationStoreVO(
                        po.getStoreId(), po.getResourceId(), po.getName(),
                        po.getType(), String.valueOf(po.getCreator()), ObjectUtils.isEmpty(po.getEditor()) ? null : String.valueOf(po.getEditor()),
                        po.getEditTime(), weight, po.getExtFlag(), po.getExtFlag1());
        }).toList();
    }

    public IPage<StorePO> queryStorePage(int goPage, int pageSize, VisualizationWorkbranchQueryRequest request) {
        Long uid = AuthUtils.getUser().getUserId();
        QueryWrapper<Object> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("s.uid", uid);
        queryWrapper.isNotNull("s.resource_id");
        if (StringUtils.isNotBlank(request.getType())) {
            BusiResourceEnum busiResourceEnum = BusiResourceEnum.valueOf(request.getType().toUpperCase());
            if (ObjectUtils.isEmpty(busiResourceEnum)) {
                DEException.throwException("type is invalid");
            }
            queryWrapper.eq("s.resource_type", busiResourceEnum.getFlag());
        }
        if (StringUtils.isNotBlank(request.getKeyword())) {
            queryWrapper.apply("LOWER(v.name) LIKE LOWER(CONCAT('%', {0}, '%'))", request.getKeyword());
        }
        String info = CommunityUtils.getInfo();
        if (StringUtils.isNotBlank(info)) {
            queryWrapper.notExists(String.format(info, "s.resource_id"));
        }
        queryWrapper.orderBy(true, request.isAsc(), "v.update_time");
        PermissionDecisionService decision = permissionDecisionService.getIfAvailable();
        if (decision != null && AuthUtils.getUser() != null && AuthUtils.getUser().getUserId() != null) {
            java.util.Set<Long> ids = decision.accessibleDashboardIds(AuthUtils.getUser().getUserId());
            if (ids.isEmpty()) {
                queryWrapper.eq("s.resource_id", -1L);
            } else {
                queryWrapper.in("s.resource_id", ids);
            }
        }
        Page<StorePO> page = new Page<>(goPage, pageSize);
        return coreStoreExtMapper.query(page, queryWrapper);
    }
}
