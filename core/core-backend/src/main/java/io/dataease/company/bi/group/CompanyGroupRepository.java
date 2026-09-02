package io.dataease.company.bi.group;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyUserStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyGroupRepository {

    private final CompanyBiGroupMapper groupMapper;
    private final CompanyBiGroupMemberMapper memberMapper;

    public CompanyGroupRepository(CompanyBiGroupMapper groupMapper, CompanyBiGroupMemberMapper memberMapper) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
    }

    public List<Long> listEnabledGroupIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        QueryWrapper<CompanyBiGroupMember> memberWrapper = new QueryWrapper<>();
        memberWrapper.eq("user_id", userId);
        List<CompanyBiGroupMember> members = memberMapper.selectList(memberWrapper);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> groupIds = members.stream().map(CompanyBiGroupMember::getGroupId).collect(Collectors.toList());
        QueryWrapper<CompanyBiGroup> groupWrapper = new QueryWrapper<>();
        groupWrapper.in("id", groupIds).eq("status", CompanyUserStatus.ENABLED.name());
        List<CompanyBiGroup> groups = groupMapper.selectList(groupWrapper);
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<Long> enabled = new ArrayList<>();
        for (CompanyBiGroup group : groups) {
            enabled.add(group.getId());
        }
        return enabled;
    }

    public void insertGroup(CompanyBiGroup group) {
        groupMapper.insert(group);
    }

    public void insertMember(CompanyBiGroupMember member) {
        memberMapper.insert(member);
    }

    public void deleteMember(Long userId, Long groupId) {
        if (userId == null || groupId == null) {
            return;
        }
        QueryWrapper<CompanyBiGroupMember> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("group_id", groupId);
        memberMapper.delete(wrapper);
    }

    public CompanyBiGroup findById(Long id) {
        if (id == null) {
            return null;
        }
        return groupMapper.selectById(id);
    }

    public CompanyBiGroup findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        QueryWrapper<CompanyBiGroup> wrapper = new QueryWrapper<>();
        wrapper.eq("name", name).last("LIMIT 1");
        return groupMapper.selectOne(wrapper);
    }

    public List<CompanyBiGroup> listAll() {
        QueryWrapper<CompanyBiGroup> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        List<CompanyBiGroup> rows = groupMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    public List<CompanyBiGroup> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<CompanyBiGroup> rows = groupMapper.selectBatchIds(ids);
        return rows == null ? List.of() : rows;
    }

    public void updateById(CompanyBiGroup group) {
        groupMapper.updateById(group);
    }

    public boolean existsMember(Long userId, Long groupId) {
        if (userId == null || groupId == null) {
            return false;
        }
        QueryWrapper<CompanyBiGroupMember> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("group_id", groupId).last("LIMIT 1");
        return memberMapper.selectCount(wrapper) > 0;
    }

    public List<CompanyBiGroupMember> listMembers(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        QueryWrapper<CompanyBiGroupMember> wrapper = new QueryWrapper<>();
        wrapper.eq("group_id", groupId).orderByAsc("id");
        List<CompanyBiGroupMember> rows = memberMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    public List<CompanyBiGroupMember> listMemberships(Long userId) {
        if (userId == null) {
            return List.of();
        }
        QueryWrapper<CompanyBiGroupMember> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        List<CompanyBiGroupMember> rows = memberMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }
}
