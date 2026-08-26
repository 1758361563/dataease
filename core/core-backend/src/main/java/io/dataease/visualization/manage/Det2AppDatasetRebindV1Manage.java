package io.dataease.visualization.manage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.api.dataset.union.UnionDTO;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.api.visualization.vo.AppCoreDatasetGroupVO;
import io.dataease.api.visualization.vo.AppCoreDatasetTableFieldVO;
import io.dataease.api.visualization.vo.AppCoreDatasetTableVO;
import io.dataease.api.visualization.vo.VisualizationExport2AppVO;
import io.dataease.dataset.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.entity.CoreDatasetTable;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetTableMapper;
import io.dataease.dataset.manage.DatasetTableFieldManage;
import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.dto.DatasetTableDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import io.dataease.utils.BeanUtils;
import io.dataease.utils.JsonUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DET2APP 单表 Dataset Rebind V1：CSV 本地单表 Source → 用户选定的单表 MySQL Target，0 手拖字段。
 */
@Service
public class Det2AppDatasetRebindV1Manage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private DatasetTableFieldManage datasetTableFieldManage;
    @Resource
    private CoreDatasetTableMapper coreDatasetTableMapper;
    @Resource
    private CoreDatasetGroupMapper coreDatasetGroupMapper;

    /**
     * 满足单表 V1 条件时执行 Rebind 并填充官方 saveCanvas 所需的 id 映射表；否则返回 false，走官方 appDatasetMatch。
     */
    public boolean tryRebind(
            VisualizationExport2AppVO appData,
            DataVisualizationBaseRequest request,
            Map<Long, Long> dsGroupIdMap,
            Map<Long, Long> dsTableIdMap,
            Map<Long, Long> dsTableFieldsIdMap,
            Map<String, String> dsTableFieldsDatasetNameMap,
            Map<Long, Long> datasourceIdMap) {
        if (appData == null || !isV1Eligible(appData)) {
            return false;
        }
        List<Det2AppRebindError> errors = new ArrayList<>();
        Map<Long, DatasetTableFieldDTO> physicalFieldMap = new HashMap<>();
        Map<Long, List<DatasetTableFieldDTO>> targetSnapshots = new HashMap<>();

        List<AppCoreDatasetGroupVO> datasetGroups = appData.getDatasetGroupsInfo().stream()
                .filter(g -> "dataset".equals(g.getNodeType()))
                .collect(Collectors.toList());

        Map<Long, List<AppCoreDatasetTableVO>> sourceTablesByGroup = appData.getDatasetTablesInfo().stream()
                .collect(Collectors.groupingBy(AppCoreDatasetTableVO::getDatasetGroupId));

        Set<Long> activeFieldIds = collectActiveFieldIds(request.getCanvasViewInfo(), request.getComponentData());

        for (AppCoreDatasetGroupVO sourceGroup : datasetGroups) {
            Long targetGroupId = sourceGroup.getSystemDatasetId();
            List<AppCoreDatasetTableVO> sourceTables = sourceTablesByGroup.getOrDefault(sourceGroup.getId(), List.of());
            if (sourceTables.size() != 1) {
                return false;
            }
            CoreDatasetTable targetTable = getSingleTargetTable(targetGroupId);
            if (targetTable == null) {
                return false;
            }
            AppCoreDatasetTableVO sourceTable = sourceTables.get(0);

            dsGroupIdMap.put(sourceGroup.getId(), targetGroupId);
            dsTableIdMap.put(sourceTable.getId(), targetTable.getId());
            datasourceIdMap.put(sourceTable.getDatasourceId(), targetTable.getDatasourceId());

            buildPhysicalFieldMap(
                    appData.getDatasetTableFieldsInfo(),
                    sourceGroup.getId(),
                    targetGroupId,
                    physicalFieldMap,
                    dsTableFieldsIdMap,
                    dsTableFieldsDatasetNameMap,
                    activeFieldIds,
                    errors);

            targetSnapshots.put(sourceGroup.getId(), buildTargetSnapshotFields(targetGroupId));
        }

        validateActiveFields(activeFieldIds, appData.getDatasetTableFieldsInfo(), physicalFieldMap, errors);

        if (!errors.isEmpty()) {
            throwPreflightErrors(errors);
        }

        rebindChartViews(request.getCanvasViewInfo(), physicalFieldMap, dsGroupIdMap);
        clearChartDataCache(request.getCanvasViewInfo());

        for (AppCoreDatasetGroupVO sourceGroup : datasetGroups) {
            Long targetGroupId = sourceGroup.getSystemDatasetId();
            replaceDatasetGroupInfoSnapshot(sourceGroup, targetGroupId, targetSnapshots.get(sourceGroup.getId()));
        }

        String componentData = rebindComponentData(
                request.getComponentData(),
                physicalFieldMap,
                dsTableFieldsIdMap,
                datasetGroups,
                targetSnapshots);
        request.setComponentData(componentData);

        return true;
    }

    private boolean isV1Eligible(VisualizationExport2AppVO appData) {
        List<AppCoreDatasetGroupVO> datasetGroups = appData.getDatasetGroupsInfo();
        if (CollectionUtils.isEmpty(datasetGroups)) {
            return false;
        }
        List<AppCoreDatasetGroupVO> sources = datasetGroups.stream()
                .filter(g -> "dataset".equals(g.getNodeType()))
                .collect(Collectors.toList());
        if (sources.isEmpty()) {
            return false;
        }
        Map<Long, List<AppCoreDatasetTableVO>> byGroup = appData.getDatasetTablesInfo().stream()
                .collect(Collectors.groupingBy(AppCoreDatasetTableVO::getDatasetGroupId));

        for (AppCoreDatasetGroupVO group : sources) {
            if (group.getSystemDatasetId() == null) {
                return false;
            }
            if (!isSingleTableUnion(group.getInfo())) {
                return false;
            }
            List<AppCoreDatasetTableVO> tables = byGroup.getOrDefault(group.getId(), List.of());
            if (tables.size() != 1) {
                return false;
            }
            if (getSingleTargetTable(group.getSystemDatasetId()) == null) {
                return false;
            }
            CoreDatasetGroup targetGroup = coreDatasetGroupMapper.selectById(group.getSystemDatasetId());
            if (targetGroup == null || !isSingleTableUnion(targetGroup.getInfo())) {
                return false;
            }
        }
        return true;
    }

    private boolean isSingleTableUnion(String infoJson) {
        if (StringUtils.isBlank(infoJson)) {
            return false;
        }
        List<UnionDTO> unions = JsonUtil.parseList(infoJson, new TypeReference<List<UnionDTO>>() {});
        if (unions == null || unions.size() != 1) {
            return false;
        }
        UnionDTO root = unions.get(0);
        if (root.getAllChildCount() > 0) {
            return false;
        }
        return root.getChildrenDs() == null || root.getChildrenDs().isEmpty();
    }

    private CoreDatasetTable getSingleTargetTable(Long targetGroupId) {
        QueryWrapper<CoreDatasetTable> wrapper = new QueryWrapper<>();
        wrapper.eq("dataset_group_id", targetGroupId);
        List<CoreDatasetTable> tables = coreDatasetTableMapper.selectList(wrapper);
        if (tables == null || tables.size() != 1) {
            return null;
        }
        return tables.get(0);
    }

    private void buildPhysicalFieldMap(
            List<AppCoreDatasetTableFieldVO> allSourceFields,
            Long sourceGroupId,
            Long targetGroupId,
            Map<Long, DatasetTableFieldDTO> physicalFieldMap,
            Map<Long, Long> dsTableFieldsIdMap,
            Map<String, String> dsTableFieldsDatasetNameMap,
            Set<Long> activeFieldIds,
            List<Det2AppRebindError> errors) {
        List<DatasetTableFieldDTO> targetFields = datasetTableFieldManage.selectByDatasetGroupId(targetGroupId);
        Map<String, List<DatasetTableFieldDTO>> targetByOrigin = targetFields.stream()
                .filter(f -> StringUtils.isNotBlank(f.getOriginName()))
                .collect(Collectors.groupingBy(DatasetTableFieldDTO::getOriginName));

        for (AppCoreDatasetTableFieldVO src : allSourceFields) {
            if (src.getDatasetGroupId() == null || !src.getDatasetGroupId().equals(sourceGroupId)) {
                continue;
            }
            if (src.getExtField() == null || src.getExtField() != 0) {
                continue;
            }
            boolean active = src.getId() != null && activeFieldIds.contains(src.getId());
            String origin = src.getOriginName();
            List<DatasetTableFieldDTO> matches = targetByOrigin.get(origin);
            if (matches == null || matches.isEmpty()) {
                if (active) {
                    errors.add(Det2AppRebindError.field(
                            "FIELD_NOT_FOUND",
                            "Target 数据集缺少 originName 字段: " + origin,
                            origin,
                            src.getId()));
                }
                continue;
            }
            if (matches.size() > 1) {
                if (active) {
                    errors.add(Det2AppRebindError.field(
                            "FIELD_AMBIGUOUS",
                            "Target originName 不唯一: " + origin,
                            origin,
                            src.getId()));
                }
                continue;
            }
            DatasetTableFieldDTO tgt = matches.get(0);
            if (!isGroupTypeCompatible(src.getGroupType(), tgt.getGroupType())) {
                if (active) {
                    errors.add(Det2AppRebindError.field(
                            "GROUP_TYPE_CONFLICT",
                            "groupType 不兼容: " + origin + " source=" + src.getGroupType() + " target=" + tgt.getGroupType(),
                            origin,
                            src.getId()));
                }
                continue;
            }
            if (!isDeTypeCompatible(src.getDeType(), tgt.getDeType())) {
                if (active) {
                    errors.add(Det2AppRebindError.field(
                            "DE_TYPE_CONFLICT",
                            "deType 不兼容: " + origin + " source=" + src.getDeType() + " target=" + tgt.getDeType(),
                            origin,
                            src.getId()));
                }
                continue;
            }
            physicalFieldMap.put(src.getId(), tgt);
            dsTableFieldsIdMap.put(src.getId(), tgt.getId());
            if (StringUtils.isNotBlank(src.getDataeaseName()) && StringUtils.isNotBlank(tgt.getDataeaseName())) {
                dsTableFieldsDatasetNameMap.put(src.getDataeaseName(), tgt.getDataeaseName());
            }
        }
    }

    private List<DatasetTableFieldDTO> buildTargetSnapshotFields(Long targetGroupId) {
        List<DatasetTableFieldDTO> fields = datasetTableFieldManage.selectByDatasetGroupId(targetGroupId);
        for (DatasetTableFieldDTO f : fields) {
            f.setFieldShortName(f.getDataeaseName());
        }
        return fields;
    }

    private void validateActiveFields(
            Set<Long> activeFieldIds,
            List<AppCoreDatasetTableFieldVO> sourceFields,
            Map<Long, DatasetTableFieldDTO> physicalFieldMap,
            List<Det2AppRebindError> errors) {
        Map<Long, AppCoreDatasetTableFieldVO> sourceById = sourceFields.stream()
                .collect(Collectors.toMap(AppCoreDatasetTableFieldVO::getId, f -> f, (a, b) -> a));

        for (Long fieldId : activeFieldIds) {
            if (physicalFieldMap.containsKey(fieldId)) {
                continue;
            }
            AppCoreDatasetTableFieldVO src = sourceById.get(fieldId);
            if (src == null) {
                errors.add(Det2AppRebindError.field(
                        "ACTIVE_FIELD_UNKNOWN",
                        "Active 配置引用了未知 Source 字段 id=" + fieldId,
                        null,
                        fieldId));
                continue;
            }
            if (src.getExtField() != null && src.getExtField() != 0) {
                errors.add(Det2AppRebindError.field(
                        "ACTIVE_NON_PHYSICAL",
                        "Active 配置引用了非物理字段: " + src.getName(),
                        src.getOriginName(),
                        fieldId));
            } else {
                errors.add(Det2AppRebindError.field(
                        "ACTIVE_FIELD_UNMAPPED",
                        "Active 物理字段未匹配到 Target: " + src.getOriginName(),
                        src.getOriginName(),
                        fieldId));
            }
        }
    }

    private Set<Long> collectActiveFieldIds(Map<Long, ChartViewDTO> canvasViews, String componentDataJson) {
        Set<Long> ids = new HashSet<>();
        if (canvasViews != null) {
            for (ChartViewDTO view : canvasViews.values()) {
                collectFieldIdsFromChart(view, ids);
            }
        }
        collectFieldIdsFromComponentData(componentDataJson, ids);
        return ids;
    }

    private void collectFieldIdsFromChart(ChartViewDTO view, Set<Long> ids) {
        collectFieldIdsFromList(view.getXAxis(), ids);
        collectFieldIdsFromList(view.getXAxisExt(), ids);
        collectFieldIdsFromList(view.getYAxis(), ids);
        collectFieldIdsFromList(view.getYAxisExt(), ids);
        collectFieldIdsFromList(view.getExtStack(), ids);
        collectFieldIdsFromList(view.getExtBubble(), ids);
        collectFieldIdsFromList(view.getExtLabel(), ids);
        collectFieldIdsFromList(view.getExtTooltip(), ids);
        collectFieldIdsFromList(view.getDrillFields(), ids);
        collectFieldIdsFromList(view.getViewFields(), ids);
        collectFieldIdsFromList(view.getFlowMapStartName(), ids);
        collectFieldIdsFromList(view.getFlowMapEndName(), ids);
        collectFieldIdsFromList(view.getExtColor(), ids);
    }

    private void collectFieldIdsFromList(List<ChartViewFieldDTO> fields, Set<Long> ids) {
        if (fields == null) {
            return;
        }
        for (ChartViewFieldDTO f : fields) {
            if (f != null && f.getId() != null) {
                ids.add(f.getId());
            }
        }
    }

    private void collectFieldIdsFromComponentData(String componentDataJson, Set<Long> ids) {
        if (StringUtils.isBlank(componentDataJson)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(componentDataJson);
            walkComponentForFieldIds(root, ids);
        } catch (Exception e) {
            DEException.throwException("DET2APP Rebind: componentData 解析失败");
        }
    }

    private void walkComponentForFieldIds(JsonNode node, Set<Long> ids) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walkComponentForFieldIds(child, ids);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        collectSelectedCheckedFieldMapIds(node, ids);
        JsonNode treeCheckedList = node.get("treeCheckedList");
        if (treeCheckedList != null && treeCheckedList.isArray()) {
            for (JsonNode item : treeCheckedList) {
                collectSelectedCheckedFieldMapIds(item, ids);
            }
        }
        JsonNode propValue = node.get("propValue");
        if (propValue != null) {
            walkComponentForFieldIds(propValue, ids);
        }
        JsonNode componentData = node.get("componentData");
        if (componentData != null) {
            walkComponentForFieldIds(componentData, ids);
        }
    }

    private void collectSelectedCheckedFieldMapIds(JsonNode node, Set<Long> ids) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode checkedFieldsMap = node.get("checkedFieldsMap");
        if (checkedFieldsMap == null || !checkedFieldsMap.isObject()) {
            return;
        }
        Set<String> selectedChartIds = null;
        JsonNode checkedFields = node.get("checkedFields");
        if (checkedFields != null && checkedFields.isArray()) {
            selectedChartIds = new HashSet<>();
            for (JsonNode chartIdNode : checkedFields) {
                if (chartIdNode != null && !chartIdNode.isNull()) {
                    String chartId = chartIdNode.asText();
                    if (StringUtils.isNotBlank(chartId)) {
                        selectedChartIds.add(chartId);
                    }
                }
            }
        }
        final Set<String> selected = selectedChartIds;
        checkedFieldsMap.fields().forEachRemaining(entry -> {
            if (selected != null && !selected.contains(entry.getKey())) {
                return;
            }
            JsonNode valNode = entry.getValue();
            if (valNode == null || valNode.isNull()) {
                return;
            }
            String val = valNode.asText();
            if (StringUtils.isBlank(val)) {
                return;
            }
            try {
                ids.add(Long.parseLong(val));
            } catch (NumberFormatException ignored) {
                // skip non-numeric
            }
        });
    }

    private void rebindChartViews(
            Map<Long, ChartViewDTO> canvasViews,
            Map<Long, DatasetTableFieldDTO> physicalFieldMap,
            Map<Long, Long> dsGroupIdMap) {
        if (canvasViews == null) {
            return;
        }
        for (ChartViewDTO view : canvasViews.values()) {
            Long sourceGroupKey = view.getSourceTableId() != null ? view.getSourceTableId() : view.getTableId();
            Long targetGroupId = sourceGroupKey != null ? dsGroupIdMap.get(sourceGroupKey) : null;
            if (targetGroupId == null) {
                targetGroupId = dsGroupIdMap.values().stream().findFirst().orElse(null);
            }
            if (targetGroupId != null) {
                view.setTableId(targetGroupId);
                view.setSourceTableId(targetGroupId);
            }
            rebindFieldList(view.getXAxis(), physicalFieldMap);
            rebindFieldList(view.getXAxisExt(), physicalFieldMap);
            rebindFieldList(view.getYAxis(), physicalFieldMap);
            rebindFieldList(view.getYAxisExt(), physicalFieldMap);
            rebindFieldList(view.getExtStack(), physicalFieldMap);
            rebindFieldList(view.getExtBubble(), physicalFieldMap);
            rebindFieldList(view.getExtLabel(), physicalFieldMap);
            rebindFieldList(view.getExtTooltip(), physicalFieldMap);
            rebindFieldList(view.getDrillFields(), physicalFieldMap);
            rebindFieldList(view.getViewFields(), physicalFieldMap);
            rebindFieldList(view.getFlowMapStartName(), physicalFieldMap);
            rebindFieldList(view.getFlowMapEndName(), physicalFieldMap);
            rebindFieldList(view.getExtColor(), physicalFieldMap);
        }
    }

    private void rebindFieldList(List<ChartViewFieldDTO> fields, Map<Long, DatasetTableFieldDTO> physicalFieldMap) {
        if (fields == null) {
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            ChartViewFieldDTO src = fields.get(i);
            if (src == null || src.getId() == null) {
                continue;
            }
            DatasetTableFieldDTO target = physicalFieldMap.get(src.getId());
            if (target != null) {
                fields.set(i, rebuildChartField(src, target));
            }
        }
    }

    ChartViewFieldDTO rebuildChartField(ChartViewFieldDTO sourcePlacement, DatasetTableFieldDTO targetField) {
        ChartViewFieldDTO rebuilt = new ChartViewFieldDTO();
        BeanUtils.copyBean(rebuilt, targetField);
        rebuilt.setSummary(sourcePlacement.getSummary());
        rebuilt.setSort(sourcePlacement.getSort());
        rebuilt.setFieldInstanceId(sourcePlacement.getFieldInstanceId());
        rebuilt.setMetricInstanceId(sourcePlacement.getMetricInstanceId());
        rebuilt.setChartShowName(sourcePlacement.getChartShowName());
        rebuilt.setFormatterCfg(sourcePlacement.getFormatterCfg());
        rebuilt.setCompareCalc(sourcePlacement.getCompareCalc());
        rebuilt.setFilter(sourcePlacement.getFilter());
        rebuilt.setCustomSort(sourcePlacement.getCustomSort());
        rebuilt.setDateStyle(sourcePlacement.getDateStyle());
        rebuilt.setDatePattern(sourcePlacement.getDatePattern());
        rebuilt.setDateShowFormat(sourcePlacement.getDateShowFormat());
        rebuilt.setChartType(sourcePlacement.getChartType());
        rebuilt.setLogic(sourcePlacement.getLogic());
        rebuilt.setFilterType(sourcePlacement.getFilterType());
        rebuilt.setIndex(sourcePlacement.getIndex());
        rebuilt.setChartId(null);
        rebuilt.setBusiType(sourcePlacement.getBusiType());
        rebuilt.setAgg(sourcePlacement.isAgg());
        rebuilt.setHide(sourcePlacement.isHide());
        rebuilt.setShow(sourcePlacement.getShow());
        rebuilt.setField(sourcePlacement.getField());
        if (StringUtils.isNotBlank(targetField.getDataeaseName())) {
            rebuilt.setFieldShortName(targetField.getDataeaseName());
        }
        return rebuilt;
    }

    private void clearChartDataCache(Map<Long, ChartViewDTO> canvasViews) {
        if (canvasViews == null) {
            return;
        }
        for (ChartViewDTO view : canvasViews.values()) {
            view.setData(null);
        }
    }

    private void replaceDatasetGroupInfoSnapshot(
            AppCoreDatasetGroupVO sourceGroup,
            Long targetGroupId,
            List<DatasetTableFieldDTO> targetSnapshotFields) {
        if (StringUtils.isBlank(sourceGroup.getInfo())) {
            return;
        }
        List<UnionDTO> unions = JsonUtil.parseList(sourceGroup.getInfo(), new TypeReference<List<UnionDTO>>() {});
        if (unions == null || unions.isEmpty()) {
            return;
        }
        UnionDTO root = unions.get(0);
        root.setCurrentDsFields(targetSnapshotFields);
        CoreDatasetTable targetTable = getSingleTargetTable(targetGroupId);
        if (targetTable != null && root.getCurrentDs() != null) {
            DatasetTableDTO currentDs = root.getCurrentDs();
            currentDs.setId(targetTable.getId());
            currentDs.setTableName(targetTable.getTableName());
            currentDs.setDatasourceId(targetTable.getDatasourceId());
            currentDs.setDatasetGroupId(targetGroupId);
            currentDs.setInfo(targetTable.getInfo());
            currentDs.setType(targetTable.getType());
        }
        sourceGroup.setInfo((String) JsonUtil.toJSONString(unions));
    }

    private String rebindComponentData(
            String componentDataJson,
            Map<Long, DatasetTableFieldDTO> physicalFieldMap,
            Map<Long, Long> fieldIdMap,
            List<AppCoreDatasetGroupVO> datasetGroups,
            Map<Long, List<DatasetTableFieldDTO>> targetSnapshots) {
        if (StringUtils.isBlank(componentDataJson)) {
            return componentDataJson;
        }
        try {
            JsonNode root = MAPPER.readTree(componentDataJson);
            Map<Long, Long> groupToTarget = datasetGroups.stream()
                    .collect(Collectors.toMap(AppCoreDatasetGroupVO::getId, AppCoreDatasetGroupVO::getSystemDatasetId));
            walkComponentForRebind(root, fieldIdMap, groupToTarget, targetSnapshots);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            DEException.throwException("DET2APP Rebind: componentData 重绑失败: " + e.getMessage());
            return componentDataJson;
        }
    }

    private void walkComponentForRebind(
            JsonNode node,
            Map<Long, Long> fieldIdMap,
            Map<Long, Long> groupToTarget,
            Map<Long, List<DatasetTableFieldDTO>> targetSnapshots) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walkComponentForRebind(child, fieldIdMap, groupToTarget, targetSnapshots);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) node;

        JsonNode checkedFieldsMap = obj.get("checkedFieldsMap");
        if (checkedFieldsMap != null && checkedFieldsMap.isObject()) {
            ObjectNode mapNode = (ObjectNode) checkedFieldsMap;
            mapNode.fieldNames().forEachRemaining(chartId -> {
                JsonNode valNode = mapNode.get(chartId);
                if (valNode != null && valNode.isTextual()) {
                    rebindCheckedFieldValue(mapNode, chartId, fieldIdMap);
                }
            });
        }

        JsonNode treeCheckedList = obj.get("treeCheckedList");
        if (treeCheckedList != null && treeCheckedList.isArray()) {
            for (JsonNode item : treeCheckedList) {
                walkComponentForRebind(item, fieldIdMap, groupToTarget, targetSnapshots);
            }
        }

        JsonNode dataset = obj.get("dataset");
        if (dataset != null && dataset.isObject()) {
            ObjectNode datasetNode = (ObjectNode) dataset;
            String datasetIdStr = datasetNode.has("id") ? datasetNode.get("id").asText() : null;
            if (StringUtils.isNotBlank(datasetIdStr)) {
                try {
                    Long sourceGroupId = Long.parseLong(datasetIdStr);
                    Long targetGroupId = groupToTarget.get(sourceGroupId);
                    if (targetGroupId != null) {
                        datasetNode.put("id", targetGroupId.toString());
                        List<DatasetTableFieldDTO> snapshot = targetSnapshots.get(sourceGroupId);
                        if (snapshot != null) {
                            datasetNode.set("fields", MAPPER.valueToTree(snapshot));
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // keep as-is
                }
            }
        }

        JsonNode propValue = obj.get("propValue");
        if (propValue != null) {
            walkComponentForRebind(propValue, fieldIdMap, groupToTarget, targetSnapshots);
        }
        JsonNode componentData = obj.get("componentData");
        if (componentData != null) {
            walkComponentForRebind(componentData, fieldIdMap, groupToTarget, targetSnapshots);
        }
    }

    private void rebindCheckedFieldValue(ObjectNode mapNode, String chartId, Map<Long, Long> fieldIdMap) {
        String oldVal = mapNode.get(chartId).asText();
        if (StringUtils.isBlank(oldVal)) {
            return;
        }
        try {
            Long oldId = Long.parseLong(oldVal);
            Long newId = fieldIdMap.get(oldId);
            if (newId != null) {
                mapNode.put(chartId, newId.toString());
            }
        } catch (NumberFormatException ignored) {
            // keep
        }
    }

    boolean isDeTypeCompatible(Integer sourceDeType, Integer targetDeType) {
        if (Objects.equals(sourceDeType, targetDeType)) {
            return true;
        }
        if (sourceDeType == null || targetDeType == null) {
            return false;
        }
        boolean sourceNumeric = sourceDeType == 2 || sourceDeType == 3;
        boolean targetNumeric = targetDeType == 2 || targetDeType == 3;
        return sourceNumeric && targetNumeric;
    }

    boolean isGroupTypeCompatible(String sourceGroupType, String targetGroupType) {
        if (StringUtils.equalsIgnoreCase(sourceGroupType, targetGroupType)) {
            return true;
        }
        // 宽表同一物理列在 Source/Target 上 d/q 标注不一致时，以 Target metadata 为准，不阻断
        if (StringUtils.equalsIgnoreCase(sourceGroupType, "d") && StringUtils.equalsIgnoreCase(targetGroupType, "q")) {
            return true;
        }
        if (StringUtils.equalsIgnoreCase(sourceGroupType, "q") && StringUtils.equalsIgnoreCase(targetGroupType, "d")) {
            return true;
        }
        return false;
    }

    private void throwPreflightErrors(List<Det2AppRebindError> errors) {
        StringBuilder sb = new StringBuilder("DET2APP Dataset Rebind V1 预检失败:\n");
        for (Det2AppRebindError err : errors) {
            sb.append("- [").append(err.getCode()).append("] ").append(err.getMessage()).append("\n");
        }
        DEException.throwException(sb.toString().trim());
    }
}
