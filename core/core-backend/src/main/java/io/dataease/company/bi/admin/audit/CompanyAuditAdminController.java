package io.dataease.company.bi.admin.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.audit.CompanyAuditLogVO;
import io.dataease.company.bi.audit.CompanyAuditPageVO;
import io.dataease.company.bi.audit.CompanyAuditQuery;
import io.dataease.company.bi.audit.CompanyBiAuditLog;
import io.dataease.company.bi.audit.CompanyBiAuditLogMapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/company/bi/admin/audit")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAuditAdminController {

    private final CompanyAdminGuard adminGuard;
    private final CompanyBiAuditLogMapper auditLogMapper;

    public CompanyAuditAdminController(CompanyAdminGuard adminGuard, CompanyBiAuditLogMapper auditLogMapper) {
        this.adminGuard = adminGuard;
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping
    public CompanyAuditPageVO query(@RequestParam(value = "operatorUserId", required = false) Long operatorUserId,
                                    @RequestParam(value = "actionType", required = false) String actionType,
                                    @RequestParam(value = "targetType", required = false) String targetType,
                                    @RequestParam(value = "fromTime", required = false) Long fromTime,
                                    @RequestParam(value = "toTime", required = false) Long toTime,
                                    @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                    @RequestParam(value = "pageSize", required = false, defaultValue = "20") int pageSize) {
        adminGuard.require(CompanyCapability.AUDIT_VIEW);
        CompanyAuditQuery query = new CompanyAuditQuery();
        query.setOperatorUserId(operatorUserId);
        query.setActionType(actionType);
        query.setTargetType(targetType);
        query.setFromTime(fromTime);
        query.setToTime(toTime);
        query.setPage(page);
        query.setPageSize(pageSize);
        return search(query);
    }

    CompanyAuditPageVO search(CompanyAuditQuery query) {
        int page = Math.max(query.getPage(), 1);
        int pageSize = Math.min(Math.max(query.getPageSize(), 1), 200);
        QueryWrapper<CompanyBiAuditLog> wrapper = new QueryWrapper<>();
        if (query.getOperatorUserId() != null) {
            wrapper.eq("operator_user_id", query.getOperatorUserId());
        }
        if (query.getActionType() != null && !query.getActionType().isBlank()) {
            wrapper.eq("action_type", query.getActionType().trim());
        }
        if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
            wrapper.eq("target_type", query.getTargetType().trim());
        }
        if (query.getFromTime() != null) {
            wrapper.ge("created_at", query.getFromTime());
        }
        if (query.getToTime() != null) {
            wrapper.le("created_at", query.getToTime());
        }
        Long total = auditLogMapper.selectCount(wrapper);
        wrapper.orderByDesc("created_at", "id");
        wrapper.last("LIMIT " + ((page - 1) * pageSize) + "," + pageSize);
        List<CompanyBiAuditLog> rows = auditLogMapper.selectList(wrapper);
        CompanyAuditPageVO vo = new CompanyAuditPageVO();
        vo.setTotal(total == null ? 0 : total);
        vo.setPage(page);
        vo.setPageSize(pageSize);
        List<CompanyAuditLogVO> records = new ArrayList<>();
        if (rows != null) {
            for (CompanyBiAuditLog row : rows) {
                records.add(CompanyAuditLogVO.from(row));
            }
        }
        vo.setRecords(records);
        return vo;
    }
}
