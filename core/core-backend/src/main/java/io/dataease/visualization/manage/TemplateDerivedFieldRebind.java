package io.dataease.visualization.manage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.dataease.api.visualization.vo.AppCoreDatasetTableFieldVO;
import io.dataease.dataset.utils.TableUtils;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.datasource.dto.FieldGroupDTO;
import io.dataease.extensions.datasource.dto.CalParam;
import io.dataease.utils.BeanUtils;
import io.dataease.utils.IDUtils;
import io.dataease.utils.JsonUtil;
import io.dataease.visualization.calculation.DashboardCalculationLifecycle;
import java.util.*;

/** Plans the complete dependency graph before writing any target metadata. */
public final class TemplateDerivedFieldRebind {
    private TemplateDerivedFieldRebind() {}

    static Set<Long> dependencies(AppCoreDatasetTableFieldVO field) {
        if (Objects.equals(field.getExtField(), 3)) {
            try { return Set.of(Long.valueOf(field.getOriginName())); }
            catch (Exception e) { throw new IllegalArgumentException("分组字段缺少有效来源：" + field.getName()); }
        }
        return DashboardCalculationLifecycle.references(field.getOriginName());
    }

    static void expand(Set<Long> required, List<AppCoreDatasetTableFieldVO> fields) {
        Map<Long, AppCoreDatasetTableFieldVO> byId = new HashMap<>();
        fields.forEach(field -> byId.put(field.getId(), field));
        Set<Long> done = new HashSet<>();
        for (Long id : new ArrayList<>(required)) visit(id, byId, required, done, new HashSet<>());
    }

    private static void visit(Long id, Map<Long, AppCoreDatasetTableFieldVO> fields, Set<Long> required,
                              Set<Long> done, Set<Long> path) {
        if (done.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("计算字段存在循环依赖：" + id);
        var field = fields.get(id);
        if (field != null && !Objects.equals(field.getExtField(), 0)) {
            for (Long dependency : dependencies(field)) {
                if (!fields.containsKey(dependency)) throw new IllegalArgumentException("字段「" + field.getName() + "」缺少依赖定义：" + dependency);
                required.add(dependency); visit(dependency, fields, required, done, path);
            }
        }
        path.remove(id); done.add(id);
    }

    static List<DatasetTableFieldDTO> plan(List<AppCoreDatasetTableFieldVO> sources, Set<Long> required,
            Map<Long, Long> groups, Map<Long, Long> tables, Map<Long, Long> datasources,
            Map<Long, List<DatasetTableFieldDTO>> targets, Map<Long, DatasetTableFieldDTO> mapped,
            Map<Long, Long> ids, Map<String, String> aliases) {
        List<DatasetTableFieldDTO> created = new ArrayList<>();
        Map<Long, DatasetTableFieldDTO> reused = new HashMap<>();
        for (var source : sources) {
            if (!required.contains(source.getId()) || Objects.equals(source.getExtField(), 0) || source.getChartId() != null) continue;
            if (!Set.of(1, 2, 3).contains(source.getExtField())) throw new IllegalArgumentException("不支持的字段类型：" + source.getName());
            var candidates = targets.getOrDefault(source.getDatasetGroupId(), List.of()).stream()
                    .filter(target -> Objects.equals(source.getName(), target.getName())).toList();
            if (candidates.size() > 1) throw new IllegalArgumentException("目标字段名称不唯一：" + source.getName());
            DatasetTableFieldDTO target;
            if (candidates.isEmpty()) {
                target = BeanUtils.copyBean(new DatasetTableFieldDTO(), source);
                target.setGroupList(JsonUtil.parseList(source.getGroupList() == null ? "[]" : source.getGroupList(), new TypeReference<List<FieldGroupDTO>>() {}));
                target.setParams(JsonUtil.parseList(source.getParams() == null ? "[]" : source.getParams(), new TypeReference<List<CalParam>>() {}));
                target.setId(IDUtils.snowID());
                target.setDatasetGroupId(groups.get(source.getDatasetGroupId()));
                target.setDatasetTableId(tables.get(source.getDatasetTableId()));
                target.setDatasourceId(datasources.get(source.getDatasourceId()));
                target.setDataeaseName(TableUtils.fieldNameShort(target.getId().toString()));
                target.setFieldShortName(target.getDataeaseName());
                created.add(target);
            } else {
                target = candidates.get(0);
                if (!Objects.equals(source.getExtField(), target.getExtField()) || !Objects.equals(source.getDeType(), target.getDeType()))
                    throw new IllegalArgumentException("目标同名字段类型冲突：" + source.getName());
                reused.put(source.getId(), target);
            }
            mapped.put(source.getId(), target); ids.put(source.getId(), target.getId());
            if (source.getDataeaseName() != null) aliases.put(source.getDataeaseName(), target.getDataeaseName());
        }
        // All IDs are allocated before rewriting, including forward/nested references.
        for (var source : sources) {
            if (!required.contains(source.getId()) || Objects.equals(source.getExtField(), 0) || source.getChartId() != null) continue;
            for (Long dependency : dependencies(source)) if (!ids.containsKey(dependency))
                throw new IllegalArgumentException("字段「" + source.getName() + "」依赖未匹配：" + dependency);
            String expression = Objects.equals(source.getExtField(), 3)
                    ? ids.get(Long.valueOf(source.getOriginName())).toString()
                    : DashboardCalculationLifecycle.expression(source.getOriginName(), ids);
            var target = mapped.get(source.getId());
            if (reused.containsKey(source.getId())) {
                if (!Objects.equals(expression, target.getOriginName())) throw new IllegalArgumentException("目标同名字段公式/来源不同：" + source.getName());
                if (Objects.equals(source.getExtField(), 3)) {
                    var groupsJson = JsonUtil.parseList(source.getGroupList() == null ? "[]" : source.getGroupList(), new TypeReference<List<FieldGroupDTO>>() {});
                    if (!Objects.equals(groupsJson, target.getGroupList()) || !Objects.equals(source.getOtherGroup(), target.getOtherGroup()))
                        throw new IllegalArgumentException("目标同名分组字段规则不同：" + source.getName());
                }
            } else target.setOriginName(expression);
        }
        return created;
    }
}
