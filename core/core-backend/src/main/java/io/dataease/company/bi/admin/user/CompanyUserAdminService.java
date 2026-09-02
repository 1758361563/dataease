package io.dataease.company.bi.admin.user;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.admin.CompanyAdminTx;
import io.dataease.company.bi.audit.CompanyAuditActionType;
import io.dataease.company.bi.audit.CompanyAuditLogService;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.auth.CompanyPasswordHasher;
import io.dataease.company.bi.group.CompanyBiGroupMember;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.utils.IDUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyUserAdminService {

    private final CompanyBiUserService userService;
    private final CompanyGroupRepository groupRepository;
    private final CompanyPasswordHasher passwordHasher;
    private final CompanyAuditRecorder auditRecorder;
    private final CompanyAdminTx tx;
    private final LongSupplier idGenerator;

    @Autowired
    public CompanyUserAdminService(CompanyBiUserService userService,
                                   CompanyGroupRepository groupRepository,
                                   CompanyPasswordHasher passwordHasher,
                                   CompanyAuditRecorder auditRecorder,
                                   CompanyAdminTx tx) {
        this(userService, groupRepository, passwordHasher, auditRecorder, tx, IDUtils::snowID);
    }

    public CompanyUserAdminService(CompanyBiUserService userService,
                                   CompanyGroupRepository groupRepository,
                                   CompanyPasswordHasher passwordHasher,
                                   CompanyAuditRecorder auditRecorder,
                                   CompanyAdminTx tx,
                                   LongSupplier idGenerator) {
        this.userService = userService;
        this.groupRepository = groupRepository;
        this.passwordHasher = passwordHasher;
        this.auditRecorder = auditRecorder;
        this.tx = tx;
        this.idGenerator = idGenerator;
    }

    public List<CompanyUserVO> list() {
        List<CompanyBiUser> users = userService.listAll();
        List<CompanyUserVO> result = new ArrayList<>();
        for (CompanyBiUser user : users) {
            result.add(toVo(user));
        }
        return result;
    }

    public CompanyUserVO detail(Long userId) {
        return toVo(requireUser(userId));
    }

    public CompanyUserVO create(CompanyUserCreateRequest request, CompanyAdminAuditContext context) {
        if (request == null) {
            CompanyAdminErrors.invalid("用户信息不完整");
        }
        String username = requireText(request.getUsername(), "username");
        CompanyAccountRole role = parseRole(request.getAccountRole());
        CompanyUserStatus status = parseStatus(request.getStatus() == null ? CompanyUserStatus.ENABLED.name() : request.getStatus());
        String password = requireText(request.getPassword(), "password");
        if (userService.usernameTaken(username, null)) {
            CompanyAdminErrors.conflict("用户名已存在");
        }
        CompanyBiUser user = new CompanyBiUser();
        user.setId(nextUserId());
        user.setUsername(username);
        user.setDisplayName(request.getDisplayName() == null ? username : request.getDisplayName().trim());
        user.setPasswordHash(passwordHasher.hash(password));
        user.setAccountRole(role.name());
        user.setStatus(status.name());
        long now = System.currentTimeMillis();
        user.setCreatedAt(now);
        user.setCreatedBy(context.operatorUserId());
        user.setUpdatedAt(now);
        user.setUpdatedBy(context.operatorUserId());
        return tx.execute(() -> {
            try {
                userService.insert(user);
            } catch (DuplicateKeyException e) {
                CompanyAdminErrors.conflict("用户名已存在");
            }
            auditRecorder.record(context, CompanyAuditActionType.USER_CREATE, "USER", user.getId(),
                    null, CompanyAuditLogService.json(userSnapshot(user)));
            return toVo(user);
        });
    }

    public CompanyUserVO update(Long userId, CompanyUserUpdateRequest request, CompanyAdminAuditContext context) {
        CompanyBiUser user = requireUser(userId);
        if (request == null) {
            CompanyAdminErrors.invalid("用户信息不完整");
        }
        Map<String, Object> before = userSnapshot(user);
        boolean changed = false;
        if (request.getDisplayName() != null) {
            String displayName = request.getDisplayName().trim();
            if (!displayName.equals(nullToEmpty(user.getDisplayName()))) {
                user.setDisplayName(displayName);
                changed = true;
            }
        }
        if (request.getUsername() != null) {
            String username = requireText(request.getUsername(), "username");
            if (!username.equals(user.getUsername())) {
                if (userService.usernameTaken(username, userId)) {
                    CompanyAdminErrors.conflict("用户名已存在");
                }
                user.setUsername(username);
                changed = true;
            }
        }
        CompanyAccountRole newRole = null;
        if (request.getAccountRole() != null && !request.getAccountRole().isBlank()) {
            newRole = parseRole(request.getAccountRole());
            if (!newRole.name().equals(user.getAccountRole())) {
                protectBootstrapRole(userId, newRole);
                user.setAccountRole(newRole.name());
                changed = true;
            }
        }
        if (!changed) {
            return toVo(user);
        }
        user.setUpdatedAt(System.currentTimeMillis());
        user.setUpdatedBy(context.operatorUserId());
        CompanyAccountRole roleForAudit = newRole;
        return tx.execute(() -> {
            userService.updateById(user);
            Map<String, Object> after = userSnapshot(user);
            if (roleForAudit != null && !roleForAudit.name().equals(before.get("accountRole"))) {
                auditRecorder.record(context, CompanyAuditActionType.USER_ROLE_CHANGE, "USER", userId,
                        CompanyAuditLogService.json(Map.of("accountRole", before.get("accountRole"))),
                        CompanyAuditLogService.json(Map.of("accountRole", roleForAudit.name())));
            }
            auditRecorder.record(context, CompanyAuditActionType.USER_UPDATE, "USER", userId,
                    CompanyAuditLogService.json(before), CompanyAuditLogService.json(after));
            return toVo(user);
        });
    }

    public CompanyUserVO changeRole(Long userId, String accountRole, CompanyAdminAuditContext context) {
        CompanyBiUser user = requireUser(userId);
        CompanyAccountRole role = parseRole(accountRole);
        if (role.name().equals(user.getAccountRole())) {
            return toVo(user);
        }
        protectBootstrapRole(userId, role);
        String beforeRole = user.getAccountRole();
        user.setAccountRole(role.name());
        user.setUpdatedAt(System.currentTimeMillis());
        user.setUpdatedBy(context.operatorUserId());
        return tx.execute(() -> {
            userService.updateById(user);
            auditRecorder.record(context, CompanyAuditActionType.USER_ROLE_CHANGE, "USER", userId,
                    CompanyAuditLogService.json(Map.of("accountRole", beforeRole)),
                    CompanyAuditLogService.json(Map.of("accountRole", role.name())));
            return toVo(user);
        });
    }

    public CompanyUserVO enable(Long userId, CompanyAdminAuditContext context) {
        return changeStatus(userId, CompanyUserStatus.ENABLED, context);
    }

    public CompanyUserVO disable(Long userId, CompanyAdminAuditContext context) {
        protectBootstrapDisable(userId);
        return changeStatus(userId, CompanyUserStatus.DISABLED, context);
    }

    public void resetPassword(Long userId, String password, CompanyAdminAuditContext context) {
        CompanyBiUser user = requireUser(userId);
        String raw = requireText(password, "password");
        user.setPasswordHash(passwordHasher.hash(raw));
        user.setUpdatedAt(System.currentTimeMillis());
        user.setUpdatedBy(context.operatorUserId());
        tx.execute(() -> {
            userService.updateById(user);
            auditRecorder.record(context, CompanyAuditActionType.PASSWORD_RESET, "USER", userId,
                    CompanyAuditLogService.passwordChangedJson(), CompanyAuditLogService.passwordChangedJson());
            return null;
        });
    }

    private CompanyUserVO changeStatus(Long userId, CompanyUserStatus status, CompanyAdminAuditContext context) {
        CompanyBiUser user = requireUser(userId);
        if (status.name().equals(user.getStatus())) {
            return toVo(user);
        }
        String before = user.getStatus();
        user.setStatus(status.name());
        user.setUpdatedAt(System.currentTimeMillis());
        user.setUpdatedBy(context.operatorUserId());
        CompanyAuditActionType action = status == CompanyUserStatus.ENABLED
                ? CompanyAuditActionType.USER_ENABLE : CompanyAuditActionType.USER_DISABLE;
        return tx.execute(() -> {
            userService.updateById(user);
            auditRecorder.record(context, action, "USER", userId,
                    CompanyAuditLogService.json(Map.of("status", before)),
                    CompanyAuditLogService.json(Map.of("status", status.name())));
            return toVo(user);
        });
    }

    private CompanyUserVO toVo(CompanyBiUser user) {
        CompanyUserVO vo = CompanyUserVO.from(user);
        if (vo == null) {
            return null;
        }
        List<CompanyBiGroupMember> memberships = groupRepository.listMemberships(user.getId());
        vo.setGroupIds(memberships.stream().map(CompanyBiGroupMember::getGroupId).collect(Collectors.toList()));
        return vo;
    }

    private CompanyBiUser requireUser(Long userId) {
        if (userId == null) {
            CompanyAdminErrors.invalid("用户不存在");
        }
        CompanyBiUser user = userService.findById(userId);
        if (user == null) {
            CompanyAdminErrors.invalid("用户不存在");
        }
        return user;
    }

    private long nextUserId() {
        long id;
        do {
            id = idGenerator.getAsLong();
        } while (id == CompanyBiUserService.BOOTSTRAP_ADMIN_ID || userService.findById(id) != null);
        return id;
    }

    static void protectBootstrapDisable(Long userId) {
        if (CompanyBiUserService.BOOTSTRAP_ADMIN_ID == userId) {
            CompanyAdminErrors.invalid("禁止禁用 bootstrap 管理员");
        }
    }

    static void protectBootstrapRole(Long userId, CompanyAccountRole role) {
        if (CompanyBiUserService.BOOTSTRAP_ADMIN_ID == userId && role != CompanyAccountRole.ADMIN) {
            CompanyAdminErrors.invalid("禁止降低 bootstrap 管理员角色");
        }
    }

    public static CompanyAccountRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            CompanyAdminErrors.invalid("账号角色无效");
        }
        try {
            return CompanyAccountRole.valueOf(raw.trim());
        } catch (RuntimeException ignored) {
            CompanyAdminErrors.invalid("账号角色无效");
            return null;
        }
    }

    public static CompanyUserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            CompanyAdminErrors.invalid("账号状态无效");
        }
        try {
            return CompanyUserStatus.valueOf(raw.trim());
        } catch (RuntimeException ignored) {
            CompanyAdminErrors.invalid("账号状态无效");
            return null;
        }
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            CompanyAdminErrors.invalid(field + " 不能为空");
        }
        return value.trim();
    }

    private static Map<String, Object> userSnapshot(CompanyBiUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("displayName", user.getDisplayName());
        map.put("accountRole", user.getAccountRole());
        map.put("status", user.getStatus());
        return map;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
