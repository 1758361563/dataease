package io.dataease.company.bi.admin.group;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.admin.CompanyAdminTx;
import io.dataease.company.bi.admin.user.CompanyUserAdminService;
import io.dataease.company.bi.audit.CompanyAuditActionType;
import io.dataease.company.bi.audit.CompanyAuditLogService;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.group.CompanyBiGroup;
import io.dataease.company.bi.group.CompanyBiGroupMember;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyGroupAdminService {

    private final CompanyGroupRepository groupRepository;
    private final CompanyBiUserService userService;
    private final CompanyAuditRecorder auditRecorder;
    private final CompanyAdminTx tx;

    public CompanyGroupAdminService(CompanyGroupRepository groupRepository,
                                    CompanyBiUserService userService,
                                    CompanyAuditRecorder auditRecorder,
                                    CompanyAdminTx tx) {
        this.groupRepository = groupRepository;
        this.userService = userService;
        this.auditRecorder = auditRecorder;
        this.tx = tx;
    }

    public List<CompanyGroupVO> list() {
        List<CompanyGroupVO> result = new ArrayList<>();
        for (CompanyBiGroup group : groupRepository.listAll()) {
            CompanyGroupVO vo = CompanyGroupVO.from(group);
            vo.setMemberCount(groupRepository.listMembers(group.getId()).size());
            result.add(vo);
        }
        return result;
    }

    public CompanyGroupVO detail(Long groupId) {
        CompanyGroupVO vo = CompanyGroupVO.from(requireGroup(groupId));
        vo.setMemberCount(groupRepository.listMembers(groupId).size());
        return vo;
    }

    public CompanyGroupVO create(CompanyGroupUpsertRequest request, CompanyAdminAuditContext context) {
        if (request == null) {
            CompanyAdminErrors.invalid("用户组信息不完整");
        }
        String name = CompanyUserAdminService.requireText(request.getName(), "name");
        if (groupRepository.findByName(name) != null) {
            CompanyAdminErrors.conflict("用户组名称已存在");
        }
        CompanyUserStatus status = request.getStatus() == null || request.getStatus().isBlank()
                ? CompanyUserStatus.ENABLED : CompanyUserAdminService.parseStatus(request.getStatus());
        CompanyBiGroup group = new CompanyBiGroup();
        group.setName(name);
        group.setDescription(request.getDescription());
        group.setStatus(status.name());
        long now = System.currentTimeMillis();
        group.setCreatedAt(now);
        group.setCreatedBy(context.operatorUserId());
        group.setUpdatedAt(now);
        group.setUpdatedBy(context.operatorUserId());
        return tx.execute(() -> {
            try {
                groupRepository.insertGroup(group);
            } catch (DuplicateKeyException e) {
                CompanyAdminErrors.conflict("用户组名称已存在");
            }
            auditRecorder.record(context, CompanyAuditActionType.GROUP_CREATE, "GROUP", group.getId(),
                    null, CompanyAuditLogService.json(groupSnapshot(group)));
            CompanyGroupVO vo = CompanyGroupVO.from(group);
            vo.setMemberCount(0);
            return vo;
        });
    }

    public CompanyGroupVO update(Long groupId, CompanyGroupUpsertRequest request, CompanyAdminAuditContext context) {
        CompanyBiGroup group = requireGroup(groupId);
        if (request == null) {
            CompanyAdminErrors.invalid("用户组信息不完整");
        }
        Map<String, Object> before = groupSnapshot(group);
        boolean nameChanged = false;
        boolean statusChanged = false;
        if (request.getName() != null) {
            String name = CompanyUserAdminService.requireText(request.getName(), "name");
            if (!name.equals(group.getName())) {
                CompanyBiGroup existing = groupRepository.findByName(name);
                if (existing != null && !existing.getId().equals(groupId)) {
                    CompanyAdminErrors.conflict("用户组名称已存在");
                }
                group.setName(name);
                nameChanged = true;
            }
        }
        if (request.getDescription() != null) {
            group.setDescription(request.getDescription());
            nameChanged = true;
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            CompanyUserStatus status = CompanyUserAdminService.parseStatus(request.getStatus());
            if (!status.name().equals(group.getStatus())) {
                group.setStatus(status.name());
                statusChanged = true;
            }
        }
        if (!nameChanged && !statusChanged) {
            return detail(groupId);
        }
        group.setUpdatedAt(System.currentTimeMillis());
        group.setUpdatedBy(context.operatorUserId());
        boolean emitStatus = statusChanged;
        boolean emitUpdate = nameChanged;
        return tx.execute(() -> {
            groupRepository.updateById(group);
            Map<String, Object> after = groupSnapshot(group);
            if (emitUpdate) {
                auditRecorder.record(context, CompanyAuditActionType.GROUP_UPDATE, "GROUP", groupId,
                        CompanyAuditLogService.json(before), CompanyAuditLogService.json(after));
            }
            if (emitStatus) {
                auditRecorder.record(context, CompanyAuditActionType.GROUP_STATUS_CHANGE, "GROUP", groupId,
                        CompanyAuditLogService.json(Map.of("status", before.get("status"))),
                        CompanyAuditLogService.json(Map.of("status", group.getStatus())));
            }
            CompanyGroupVO vo = CompanyGroupVO.from(group);
            vo.setMemberCount(groupRepository.listMembers(groupId).size());
            return vo;
        });
    }

    public List<CompanyGroupMemberVO> listMembers(Long groupId) {
        requireGroup(groupId);
        List<CompanyBiGroupMember> members = groupRepository.listMembers(groupId);
        List<Long> userIds = new ArrayList<>();
        for (CompanyBiGroupMember member : members) {
            userIds.add(member.getUserId());
        }
        Map<Long, CompanyBiUser> users = new HashMap<>();
        for (CompanyBiUser user : userService.listByIds(userIds)) {
            users.put(user.getId(), user);
        }
        List<CompanyGroupMemberVO> result = new ArrayList<>();
        for (CompanyBiGroupMember member : members) {
            CompanyBiUser user = users.get(member.getUserId());
            if (user == null) {
                continue;
            }
            CompanyGroupMemberVO vo = new CompanyGroupMemberVO();
            vo.setUserId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setDisplayName(user.getDisplayName());
            vo.setAccountRole(user.getAccountRole());
            vo.setStatus(user.getStatus());
            result.add(vo);
        }
        return result;
    }

    public void addMember(Long groupId, Long userId, CompanyAdminAuditContext context) {
        requireGroup(groupId);
        if (userService.findById(userId) == null) {
            CompanyAdminErrors.invalid("用户不存在");
        }
        if (groupRepository.existsMember(userId, groupId)) {
            return;
        }
        CompanyBiGroupMember member = new CompanyBiGroupMember();
        member.setUserId(userId);
        member.setGroupId(groupId);
        member.setCreatedAt(System.currentTimeMillis());
        member.setCreatedBy(context.operatorUserId());
        tx.execute(() -> {
            try {
                groupRepository.insertMember(member);
            } catch (DuplicateKeyException ignored) {
                return null;
            }
            auditRecorder.record(context, CompanyAuditActionType.GROUP_MEMBER_ADD, "GROUP", groupId,
                    null, CompanyAuditLogService.json(Map.of("userId", userId, "groupId", groupId)));
            return null;
        });
    }

    public void removeMember(Long groupId, Long userId, CompanyAdminAuditContext context) {
        requireGroup(groupId);
        if (userService.findById(userId) == null) {
            CompanyAdminErrors.invalid("用户不存在");
        }
        boolean existed = groupRepository.existsMember(userId, groupId);
        tx.execute(() -> {
            groupRepository.deleteMember(userId, groupId);
            if (existed) {
                auditRecorder.record(context, CompanyAuditActionType.GROUP_MEMBER_REMOVE, "GROUP", groupId,
                        CompanyAuditLogService.json(Map.of("userId", userId, "groupId", groupId)), null);
            }
            return null;
        });
    }

    private CompanyBiGroup requireGroup(Long groupId) {
        if (groupId == null) {
            CompanyAdminErrors.invalid("用户组不存在");
        }
        CompanyBiGroup group = groupRepository.findById(groupId);
        if (group == null) {
            CompanyAdminErrors.invalid("用户组不存在");
        }
        return group;
    }

    private static Map<String, Object> groupSnapshot(CompanyBiGroup group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.getId());
        map.put("name", group.getName());
        map.put("description", group.getDescription());
        map.put("status", group.getStatus());
        return map;
    }
}
