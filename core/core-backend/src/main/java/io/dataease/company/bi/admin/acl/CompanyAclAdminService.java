package io.dataease.company.bi.admin.acl;

import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanyBiResourceAcl;
import io.dataease.company.bi.acl.CompanyRelationLevel;
import io.dataease.company.bi.acl.CompanyResourceType;
import io.dataease.company.bi.acl.CompanySubjectType;
import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.admin.CompanyAdminTx;
import io.dataease.company.bi.audit.CompanyAuditActionType;
import io.dataease.company.bi.audit.CompanyAuditLogService;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyBiUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAclAdminService {

    private static final int MAX_GRANT_BATCH = 50;

    private final CompanyAclRepository aclRepository;
    private final CompanyBiUserService userService;
    private final CompanyGroupRepository groupRepository;
    private final CompanyAclResourceValidator resourceValidator;
    private final CompanyAdminCatalogService catalogService;
    private final CompanyAuditRecorder auditRecorder;
    private final CompanyAdminTx tx;

    public CompanyAclAdminService(CompanyAclRepository aclRepository,
                                  CompanyBiUserService userService,
                                  CompanyGroupRepository groupRepository,
                                  CompanyAclResourceValidator resourceValidator,
                                  CompanyAdminCatalogService catalogService,
                                  CompanyAuditRecorder auditRecorder,
                                  CompanyAdminTx tx) {
        this.aclRepository = aclRepository;
        this.userService = userService;
        this.groupRepository = groupRepository;
        this.resourceValidator = resourceValidator;
        this.catalogService = catalogService;
        this.auditRecorder = auditRecorder;
        this.tx = tx;
    }

    public CompanyAdminCatalogVO catalog() {
        return catalogService.catalog();
    }

    public List<CompanyDirectAclVO> listDirect(String subjectTypeRaw, Long subjectId) {
        CompanySubjectType subjectType = parseSubject(subjectTypeRaw);
        requireSubject(subjectType, subjectId);
        List<CompanyBiResourceAcl> rows = aclRepository.listDirectBySubject(subjectType, subjectId);
        Map<CompanyResourceType, List<Long>> idsByType = new EnumMap<>(CompanyResourceType.class);
        for (CompanyBiResourceAcl row : rows) {
            CompanyResourceType type = parseResource(row.getResourceType());
            idsByType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(row.getResourceId());
        }
        Map<CompanyResourceType, Map<Long, String>> names = new EnumMap<>(CompanyResourceType.class);
        for (Map.Entry<CompanyResourceType, List<Long>> entry : idsByType.entrySet()) {
            names.put(entry.getKey(), catalogService.names(entry.getKey(), entry.getValue()));
        }
        List<CompanyDirectAclVO> result = new ArrayList<>();
        for (CompanyBiResourceAcl row : rows) {
            CompanyResourceType type = parseResource(row.getResourceType());
            CompanyDirectAclVO vo = new CompanyDirectAclVO();
            vo.setResourceType(type.name());
            vo.setResourceId(row.getResourceId());
            vo.setRelation(displayRelation(type, row.getRelationLevel()));
            Map<Long, String> typeNames = names.get(type);
            vo.setName(typeNames == null ? null : typeNames.get(row.getResourceId()));
            result.add(vo);
        }
        return result;
    }

    public CompanyAclGrantVO grant(CompanyAclMutationRequest request, CompanyAdminAuditContext context) {
        ParsedAcl parsed = parseMutation(request, true);
        requireSubject(parsed.subjectType, parsed.subjectId);
        resourceValidator.requireValid(parsed.resourceType, parsed.resourceId);
        CompanyBiResourceAcl existing = aclRepository.find(parsed.subjectType, parsed.subjectId,
                parsed.resourceType, parsed.resourceId);
        String beforeRelation = existing == null ? null : existing.getRelationLevel();
        return tx.execute(() -> {
            CompanyAclRepository.AclWriteResult write = aclRepository.upsert(
                    parsed.subjectType, parsed.subjectId, parsed.resourceType, parsed.resourceId,
                    parsed.relation, context.operatorUserId());
            if (write == CompanyAclRepository.AclWriteResult.GRANTED) {
                auditRecorder.record(context, CompanyAuditActionType.ACL_GRANT, parsed.resourceType.name(),
                        parsed.resourceId, null, auditAfter(parsed));
            } else if (write == CompanyAclRepository.AclWriteResult.CHANGED) {
                auditRecorder.record(context, CompanyAuditActionType.ACL_CHANGE, parsed.resourceType.name(),
                        parsed.resourceId,
                        CompanyAuditLogService.json(Map.of("relation", beforeRelation,
                                "subjectType", parsed.subjectType.name(), "subjectId", parsed.subjectId)),
                        auditAfter(parsed));
            }
            return toGrantVo(parsed, write);
        });
    }

    public CompanyAclGrantVO revoke(CompanyAclMutationRequest request, CompanyAdminAuditContext context) {
        ParsedAcl parsed = parseMutation(request, false);
        requireSubject(parsed.subjectType, parsed.subjectId);
        resourceValidator.requireValid(parsed.resourceType, parsed.resourceId);
        CompanyBiResourceAcl existing = aclRepository.find(parsed.subjectType, parsed.subjectId,
                parsed.resourceType, parsed.resourceId);
        String beforeRelation = existing == null ? null : existing.getRelationLevel();
        return tx.execute(() -> {
            CompanyAclRepository.AclWriteResult write = aclRepository.revoke(
                    parsed.subjectType, parsed.subjectId, parsed.resourceType, parsed.resourceId);
            if (write == CompanyAclRepository.AclWriteResult.REVOKED) {
                auditRecorder.record(context, CompanyAuditActionType.ACL_REVOKE, parsed.resourceType.name(),
                        parsed.resourceId,
                        CompanyAuditLogService.json(Map.of("relation", beforeRelation,
                                "subjectType", parsed.subjectType.name(), "subjectId", parsed.subjectId)),
                        null);
            }
            return toGrantVo(parsed, write);
        });
    }

    public void assertBatchSize(int size) {
        if (size > MAX_GRANT_BATCH) {
            CompanyAdminErrors.invalid("批量授权数量超过限制");
        }
    }

    private ParsedAcl parseMutation(CompanyAclMutationRequest request, boolean grant) {
        if (request == null) {
            CompanyAdminErrors.invalid("ACL 参数不完整");
        }
        CompanySubjectType subjectType = parseSubject(request.getSubjectType());
        CompanyResourceType resourceType = parseResource(request.getResourceType());
        if (request.getSubjectId() == null || request.getResourceId() == null) {
            CompanyAdminErrors.invalid("ACL 参数不完整");
        }
        CompanyRelationLevel relation = CompanyRelationLevel.VISITOR;
        if (grant) {
            relation = parseRelation(resourceType, request.getRelation());
        }
        return new ParsedAcl(subjectType, request.getSubjectId(), resourceType, request.getResourceId(), relation);
    }

    private void requireSubject(CompanySubjectType subjectType, Long subjectId) {
        if (subjectId == null) {
            CompanyAdminErrors.invalid("授权对象不存在");
        }
        if (subjectType == CompanySubjectType.USER && userService.findById(subjectId) == null) {
            CompanyAdminErrors.invalid("用户不存在");
        }
        if (subjectType == CompanySubjectType.GROUP && groupRepository.findById(subjectId) == null) {
            CompanyAdminErrors.invalid("用户组不存在");
        }
    }

    static CompanySubjectType parseSubject(String raw) {
        if (raw == null || raw.isBlank()) {
            CompanyAdminErrors.invalid("授权对象类型无效");
        }
        try {
            return CompanySubjectType.valueOf(raw.trim());
        } catch (RuntimeException ignored) {
            CompanyAdminErrors.invalid("授权对象类型无效");
            return null;
        }
    }

    static CompanyResourceType parseResource(String raw) {
        if (raw == null || raw.isBlank()) {
            CompanyAdminErrors.invalid("资源类型无效");
        }
        try {
            return CompanyResourceType.valueOf(raw.trim());
        } catch (RuntimeException ignored) {
            CompanyAdminErrors.invalid("资源类型无效");
            return null;
        }
    }

    static CompanyRelationLevel parseRelation(CompanyResourceType resourceType, String raw) {
        if (raw == null || raw.isBlank()) {
            CompanyAdminErrors.invalid("授权关系无效");
        }
        String value = raw.trim();
        if ("NONE".equals(value) || "DENY".equals(value)) {
            CompanyAdminErrors.invalid("不支持保存 NONE/DENY ACL");
        }
        if (resourceType == CompanyResourceType.DATASET) {
            if ("READ".equals(value) || "VISITOR".equals(value)) {
                return CompanyRelationLevel.VISITOR;
            }
            if ("OWNER".equals(value)) {
                CompanyAdminErrors.invalid("数据集不支持 OWNER");
            }
            CompanyAdminErrors.invalid("数据集授权只能是 READ");
        }
        try {
            return CompanyRelationLevel.valueOf(value);
        } catch (RuntimeException ignored) {
            CompanyAdminErrors.invalid("授权关系无效");
            return null;
        }
    }

    private static String displayRelation(CompanyResourceType type, String stored) {
        if (type == CompanyResourceType.DATASET && CompanyRelationLevel.VISITOR.name().equals(stored)) {
            return "READ";
        }
        return stored;
    }

    private static String auditAfter(ParsedAcl parsed) {
        return CompanyAuditLogService.json(Map.of(
                "subjectType", parsed.subjectType.name(),
                "subjectId", parsed.subjectId,
                "resourceType", parsed.resourceType.name(),
                "resourceId", parsed.resourceId,
                "relation", parsed.resourceType == CompanyResourceType.DATASET ? "READ" : parsed.relation.name()));
    }

    private static CompanyAclGrantVO toGrantVo(ParsedAcl parsed, CompanyAclRepository.AclWriteResult write) {
        CompanyAclGrantVO vo = new CompanyAclGrantVO();
        vo.setSubjectType(parsed.subjectType.name());
        vo.setSubjectId(parsed.subjectId);
        vo.setResourceType(parsed.resourceType.name());
        vo.setResourceId(parsed.resourceId);
        vo.setRelation(parsed.resourceType == CompanyResourceType.DATASET ? "READ" : parsed.relation.name());
        vo.setResult(write.name());
        return vo;
    }

    private record ParsedAcl(CompanySubjectType subjectType, Long subjectId, CompanyResourceType resourceType,
                             Long resourceId, CompanyRelationLevel relation) {
    }
}
