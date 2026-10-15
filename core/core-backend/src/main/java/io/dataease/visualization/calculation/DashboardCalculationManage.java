package io.dataease.visualization.calculation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.chart.dao.auto.mapper.CoreChartViewMapper;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.constant.AuthEnum;
import io.dataease.constant.CommonConstants;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.dataset.dao.auto.entity.CoreDatasetTableField;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetTableFieldMapper;
import io.dataease.dataset.manage.DatasetTableFieldManage;
import io.dataease.dataset.utils.TableUtils;
import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.system.manage.CorePermissionManage;
import io.dataease.utils.JsonUtil;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoMapper;
import io.dataease.visualization.dao.auto.mapper.SnapshotCoreChartViewMapper;
import io.dataease.visualization.dao.auto.mapper.SnapshotDataVisualizationInfoMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class DashboardCalculationManage {
    @Resource private DatasetTableFieldManage datasetTableFieldManage;
    @Resource private CoreDatasetGroupMapper datasetGroupMapper;
    @Resource private CoreDatasetTableFieldMapper fieldMapper;
    @Resource private CorePermissionManage corePermissionManage;
    @Resource private DataVisualizationInfoMapper visualizationMapper;
    @Resource private SnapshotDataVisualizationInfoMapper snapshotMapper;
    @Resource private CoreChartViewMapper chartMapper;
    @Resource private SnapshotCoreChartViewMapper snapshotChartMapper;
    @Autowired private ObjectProvider<CompanyDashboardGuard> dashboardGuard;

    public DashboardCalculationConfig normalize(String json) {
        return normalize(json, Map.of());
    }

    public DashboardCalculationConfig normalizeForCanvas(DataVisualizationBaseRequest request) {
        Map<Long, List<DatasetTableFieldDTO>> pending = new HashMap<>();
        if (request.getCanvasViewInfo() != null) {
            var charts = request.getCanvasViewInfo().values();
            for (var chart : charts) {
                if (!"template".equalsIgnoreCase(chart.getDataFrom()) || chart.getTableId() != null
                        || chart.getSourceTableId() == null || chart.getViewFields() == null) continue;
                Long source = chart.getSourceTableId();
                if (charts.stream().anyMatch(other -> Objects.equals(other.getTableId(), source))) continue;
                pending.put(source, chart.getViewFields().stream().filter(field -> field.getChartId() == null
                        && !"DASHBOARD".equals(field.getFieldScope())).map(field -> (DatasetTableFieldDTO) field).toList());
            }
        }
        // Unbound style templates validate against their included metadata and never execute SQL.
        // A live card requires the actual dataset and column permissions on the normal path.
        return normalize(request.getCalculationConfig(), pending);
    }

    private DashboardCalculationConfig normalize(String json, Map<Long, List<DatasetTableFieldDTO>> pending) {
        try {
            DashboardCalculationConfig config = parse(json);
            Map<Long, List<DatasetTableFieldDTO>> fields = new LinkedHashMap<>();
            if (config.getDatasetFields() == null) throw new IllegalArgumentException("共享字段列表不能为空");
            for (var field : config.getDatasetFields()) {
                if (field == null || field.getDatasetGroupId() == null) throw new IllegalArgumentException("共享字段必须指定数据集");
                Long datasetId = field.getDatasetGroupId();
                if (!fields.containsKey(datasetId)) {
                    if (pending.containsKey(datasetId)) {
                        fields.put(datasetId, pending.get(datasetId));
                        continue;
                    }
                    if (datasetGroupMapper.selectById(datasetId) == null) throw new IllegalArgumentException("数据集不存在：" + datasetId);
                    BusiPerCheckDTO check = new BusiPerCheckDTO();
                    check.setId(datasetId);
                    check.setAuthEnum(AuthEnum.READ);
                    if (!corePermissionManage.checkAuth(check)) throw new IllegalArgumentException("无数据集访问权限：" + datasetId);
                    fields.put(datasetId, datasetTableFieldManage.listFieldsWithPermissions(datasetId));
                }
            }
            return new DashboardCalculationValidator().validate(config, fields);
        } catch (Exception e) {
            DEException.throwException("仪表板计算配置无效：" + e.getMessage());
            return null;
        }
    }

    public DashboardCalculationConfig validateForEdit(DataVisualizationBaseRequest request) {
        CompanyDashboardGuard guard = dashboardGuard.getIfAvailable();
        requireScope(request.getId(), true, guard);
        var config = normalizeForCanvas(request);
        validateRemoval(request, config);
        return config;
    }

    public Map<String, String> defaults(DataVisualizationBaseRequest request) {
        return new DashboardParameterResolver().defaults(validateForEdit(request), Instant.now());
    }

    /** Omitted config preserves definitions when older clients save a canvas. Empty lists explicitly clear it. */
    public void prepareSave(DataVisualizationBaseRequest request) {
        if (request.getCalculationConfig() != null) {
            DashboardCalculationConfig config = validateForEdit(request);
            request.setCalculationConfig((String) JsonUtil.toJSONString(config));
        }
    }

    private void validateRemoval(DataVisualizationBaseRequest request, DashboardCalculationConfig config) {
        var previous = snapshotMapper.selectById(request.getId());
        if (previous != null) {
            Set<Long> retained = definitionIds(config);
            Set<Long> removed = definitionIds(parse(previous.getCalculationConfig()));
            removed.removeAll(retained);
            if (!removed.isEmpty() && request.getCanvasViewInfo() != null) {
                for (var chart : request.getCanvasViewInfo().values()) {
                    rejectRemovedReferences((String) JsonUtil.toJSONString(chart), removed);
                }
                if (!request.getCanvasViewInfo().isEmpty()) {
                    QueryWrapper<CoreDatasetTableField> query = new QueryWrapper<>();
                    query.in("chart_id", request.getCanvasViewInfo().keySet());
                    for (var field : fieldMapper.selectList(query)) rejectRemovedReferences(field.getOriginName(), removed);
                }
            }
        }
    }

    private static Set<Long> definitionIds(DashboardCalculationConfig config) {
        Set<Long> ids = new HashSet<>();
        config.getParameters().forEach(parameter -> ids.add(parameter.getId()));
        config.getDatasetFields().forEach(field -> ids.add(field.getId()));
        return ids;
    }

    static void rejectRemovedReferences(String expression, Set<Long> removed) {
        if (expression == null) return;
        for (Long id : removed) {
            if (expression.contains("[" + id + "]") || expression.contains("[DVP." + id + "]")
                    || java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?" + id + "(?:\"|[,}])").matcher(expression).find()) {
                DEException.throwException("参数/共享字段仍被图表使用，不能删除：" + id);
            }
        }
    }

    public static DashboardCalculationConfig parse(String json) {
        if (json == null || json.isBlank()) return new DashboardCalculationConfig();
        DashboardCalculationConfig config = JsonUtil.parseObject(json, DashboardCalculationConfig.class);
        if (config == null) throw new IllegalArgumentException("配置必须是JSON对象");
        return config;
    }

    public List<ChartViewFieldDTO> sharedFields(Long datasetId, Long chartId, ChartViewDTO request) {
        if (request == null) return List.of();
        Long requestedScene = request.getSceneId();
        try {
            var config = configurationForChart(datasetId, chartId, request);
            return toFields(config, request.getSceneId(), datasetId);
        } finally {
            request.setSceneId(requestedScene);
        }
    }

    public DashboardCalculationConfig configurationForChart(Long datasetId, Long chartId, ChartViewDTO request) {
        if (!Set.of("core", "snapshot").contains(request.getResourceTable())) throw new IllegalArgumentException("无效的可视化版本来源");
        boolean snapshot = CommonConstants.RESOURCE_TABLE.SNAPSHOT.equals(request.getResourceTable());
        Long sceneId;
        Long actualDatasetId;
        if (snapshot) {
            var chart = snapshotChartMapper.selectById(chartId);
            sceneId = chart == null ? null : chart.getSceneId();
            actualDatasetId = chart == null ? null : chart.getTableId();
        } else {
            var chart = chartMapper.selectById(chartId);
            sceneId = chart == null ? null : chart.getSceneId();
            actualDatasetId = chart == null ? null : chart.getTableId();
        }
        CompanyDashboardGuard guard = dashboardGuard.getIfAvailable();
        if (sceneId == null) {
            // New cards only have an editor scene. Never accept it as a read-only saved-card override.
            sceneId = request.getSceneId();
            if (sceneId == null) return new DashboardCalculationConfig();
            if (!snapshot) throw new IllegalArgumentException("正式图表不存在");
        } else {
            // Editors can switch a card to a different dataset before saving the card.
            if (!Objects.equals(actualDatasetId, datasetId) && !snapshot) throw new IllegalArgumentException("图表与数据集不匹配");
            if (request.getSceneId() != null && !Objects.equals(sceneId, request.getSceneId())) throw new IllegalArgumentException("图表与仪表板不匹配");
        }
        String json;
        if (snapshot) {
            var visualization = snapshotMapper.selectById(sceneId);
            json = visualization == null ? null : visualization.getCalculationConfig();
        } else {
            var visualization = visualizationMapper.selectById(sceneId);
            json = visualization == null ? null : visualization.getCalculationConfig();
        }
        request.setSceneId(sceneId);
        if (snapshot) {
            requireScope(sceneId, true, guard);
            if (request.getCalculationConfig() != null) return normalize(request.getCalculationConfig());
        } else if (request.getCalculationConfig() != null) {
            throw new IllegalArgumentException("正式查询不能覆盖仪表板计算定义");
        }
        if (json == null || json.isBlank()) return new DashboardCalculationConfig();
        if (!snapshot) requireScope(sceneId, false, guard);
        return parse(json);
    }

    public Map<String, Object> runtime(DataVisualizationBaseRequest request) {
        if (!Set.of("core", "snapshot").contains(request.getResourceTable())) throw new IllegalArgumentException("无效的可视化版本来源");
        boolean snapshot = "snapshot".equals(request.getResourceTable());
        requireScope(request.getId(), snapshot, dashboardGuard.getIfAvailable());
        String json;
        if (snapshot) {
            var saved = snapshotMapper.selectById(request.getId());
            json = saved == null ? null : saved.getCalculationConfig();
        } else {
            var saved = visualizationMapper.selectById(request.getId());
            json = saved == null ? null : saved.getCalculationConfig();
        }
        DashboardCalculationConfig config = snapshot && request.getCalculationConfig() != null
                ? normalizeForCanvas(request) : parse(json);
        Instant reference = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", config);
        result.put("reference", reference.toString());
        result.put("values", new DashboardParameterResolver().defaults(config, reference));
        return result;
    }

    public void bindQuery(ChartViewDTO view, List<ChartViewFieldDTO> fields) {
        var config = configurationForChart(view.getTableId(), view.getId(), view);
        var ext = view.getChartExtRequest();
        String suppliedReference = view.getDashboardParameterReference() != null ? view.getDashboardParameterReference()
                : ext == null ? null : ext.getDashboardParameterReference();
        var values = view.getDashboardParameterValues() != null ? view.getDashboardParameterValues()
                : ext == null ? null : ext.getDashboardParameterValues();
        Instant reference = suppliedReference == null ? Instant.now() : Instant.parse(suppliedReference);
        if (!config.getParameters().isEmpty() || !config.getDatasetFields().isEmpty()) {
            // Existing cache integrations do not include dashboard definitions and per-viewer values.
            if (ext != null) ext.setCache(false);
        }
        new DashboardParameterCompiler().bind(config, values, reference, fields);
    }

    private void requireScope(Long sceneId, boolean edit, CompanyDashboardGuard guard) {
        if (sceneId == null) throw new IllegalArgumentException("仪表板ID不能为空");
        if (guard != null) {
            if (edit) guard.requireEdit(sceneId); else guard.requireView(sceneId);
        } else {
            BusiPerCheckDTO check = new BusiPerCheckDTO();
            check.setId(sceneId);
            check.setAuthEnum(edit ? AuthEnum.MANAGE : AuthEnum.READ);
            if (!corePermissionManage.checkAuth(check)) DEException.throwException("无仪表板访问权限");
        }
    }

    /** Definitions stay unbound here; each query compiles its own copy. */
    public static List<ChartViewFieldDTO> toFields(DashboardCalculationConfig config, Long sceneId, Long datasetId) {
        List<ChartViewFieldDTO> result = new ArrayList<>();
        for (var field : config.getDatasetFields()) {
            if (!Objects.equals(field.getDatasetGroupId(), datasetId)) continue;
            ChartViewFieldDTO dto = new ChartViewFieldDTO();
            dto.setId(field.getId());
            dto.setDatasetGroupId(datasetId);
            dto.setDashboardId(sceneId);
            dto.setFieldScope("DASHBOARD");
            dto.setName(field.getName());
            dto.setOriginName(field.getExpression());
            dto.setDataeaseName(TableUtils.fieldNameShort(field.getId().toString()));
            dto.setFieldShortName(dto.getDataeaseName());
            dto.setExtField(2);
            dto.setDeType(field.getDeType());
            dto.setDeExtractType(field.getDeType());
            dto.setGroupType(field.getGroupType());
            dto.setChecked(true);
            dto.setSort("none");
            dto.setSummary("q".equals(field.getGroupType()) ? "sum" : "");
            result.add(dto);
        }
        return result;
    }
}
