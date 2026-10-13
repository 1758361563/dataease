package io.dataease.visualization.calculation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.dataease.utils.*;
import io.dataease.visualization.dao.auto.entity.*;
import io.dataease.visualization.dao.auto.mapper.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Function;

/** Copy linkage/jump graphs with the same version and exact ID mapping as their cards. */
@Component
public class DashboardCalculationCopyRelations {
    @Resource private VisualizationLinkageMapper linkages;
    @Resource private SnapshotVisualizationLinkageMapper draftLinkages;
    @Resource private VisualizationLinkageFieldMapper linkageFields;
    @Resource private SnapshotVisualizationLinkageFieldMapper draftLinkageFields;
    @Resource private VisualizationLinkJumpMapper jumps;
    @Resource private SnapshotVisualizationLinkJumpMapper draftJumps;
    @Resource private VisualizationLinkJumpInfoMapper jumpInfos;
    @Resource private SnapshotVisualizationLinkJumpInfoMapper draftJumpInfos;
    @Resource private VisualizationLinkJumpTargetViewInfoMapper jumpTargets;
    @Resource private SnapshotVisualizationLinkJumpTargetViewInfoMapper draftJumpTargets;

    public void copy(Long source, Long target, Long copyId, boolean draft, Map<Long, Long> cards,
                     DashboardCalculationLifecycle.Remapped fields) {
        var parents = load(linkages, draftLinkages, VisualizationLinkage.class, "dv_id", List.of(source), draft);
        var children = load(linkageFields, draftLinkageFields, VisualizationLinkageField.class, "linkage_id", parents.stream().map(VisualizationLinkage::getId).toList(), draft);
        var jumpParents = load(jumps, draftJumps, VisualizationLinkJump.class, "source_dv_id", List.of(source), draft);
        var infos = load(jumpInfos, draftJumpInfos, VisualizationLinkJumpInfo.class, "link_jump_id", jumpParents.stream().map(VisualizationLinkJump::getId).toList(), draft);
        var targets = load(jumpTargets, draftJumpTargets, VisualizationLinkJumpTargetViewInfo.class, "link_jump_info_id", infos.stream().map(VisualizationLinkJumpInfo::getId).toList(), draft);
        var ids = new HashMap<>(fields.ids()); ids.putAll(cards); ids.put(source, target);
        parents.forEach(row -> ids.put(row.getId(), IDUtils.snowID())); children.forEach(row -> ids.put(row.getId(), IDUtils.snowID()));
        jumpParents.forEach(row -> ids.put(row.getId(), IDUtils.snowID())); infos.forEach(row -> ids.put(row.getId(), IDUtils.snowID()));
        targets.forEach(row -> ids.put(row.getTargetId(), IDUtils.snowID()));
        var mapped = new DashboardCalculationLifecycle.Remapped(fields.config(), ids, fields.aliases());
        store(parents, linkages, draftLinkages, VisualizationLinkage.class, SnapshotVisualizationLinkage.class, mapped, target, copyId, VisualizationLinkage::getId);
        store(children, linkageFields, draftLinkageFields, VisualizationLinkageField.class, SnapshotVisualizationLinkageField.class, mapped, target, copyId, VisualizationLinkageField::getId);
        store(jumpParents, jumps, draftJumps, VisualizationLinkJump.class, SnapshotVisualizationLinkJump.class, mapped, target, copyId, VisualizationLinkJump::getId);
        store(infos, jumpInfos, draftJumpInfos, VisualizationLinkJumpInfo.class, SnapshotVisualizationLinkJumpInfo.class, mapped, target, copyId, VisualizationLinkJumpInfo::getId);
        store(targets, jumpTargets, draftJumpTargets, VisualizationLinkJumpTargetViewInfo.class, SnapshotVisualizationLinkJumpTargetViewInfo.class, mapped, target, copyId, VisualizationLinkJumpTargetViewInfo::getTargetId);
    }

    private <T, S> List<T> load(BaseMapper<T> live, BaseMapper<S> snapshots, Class<T> type, String column, List<Long> ids, boolean draft) {
        if (ids.isEmpty()) return List.of();
        if (!draft) return live.selectList(new QueryWrapper<T>().in(column, ids));
        return snapshots.selectList(new QueryWrapper<S>().in(column, ids)).stream()
                .map(row -> JsonUtil.parseObject((String) JsonUtil.toJSONString(row), type)).toList();
    }

    private <T, S> void store(List<T> rows, BaseMapper<T> live, BaseMapper<S> snapshots, Class<T> type, Class<S> snapshotType,
                              DashboardCalculationLifecycle.Remapped map, Long target, Long copyId, Function<T, Long> identity) {
        for (T row : rows) {
            T copied = DashboardCalculationLifecycle.object(row, type, map, target);
            BeanUtils.setFieldValueByName(copied, "copyFrom", identity.apply(row), Long.class);
            BeanUtils.setFieldValueByName(copied, "copyId", copyId, Long.class);
            live.insert(copied);
            snapshots.insert(JsonUtil.parseObject((String) JsonUtil.toJSONString(copied), snapshotType));
        }
    }
}
