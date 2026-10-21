package io.dataease.company.bi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.template.dto.TemplateManageFileDTO;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.api.visualization.vo.VisualizationExport2AppVO;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.company.bi.permission.CompanyDatasetDesignGuard;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.exception.DEException;
import io.dataease.visualization.server.DataVisualizationServer;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Imports configuration into a draft, rebinding only to explicitly selected existing datasets. */
@RestController
@RequestMapping("/company/bi/migration")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardMigrationServer {
    private final DataVisualizationServer visualizations;
    private final CompanyDashboardGuard guard;
    private final CompanyDatasetDesignGuard datasets;
    private final CoreDatasetGroupMapper datasetMapper;
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CompanyDashboardMigrationServer(DataVisualizationServer visualizations, CompanyDashboardGuard guard,
                                           CompanyDatasetDesignGuard datasets, CoreDatasetGroupMapper datasetMapper) {
        this.visualizations = visualizations;
        this.guard = guard;
        this.datasets = datasets;
        this.datasetMapper = datasetMapper;
    }

    @Data
    public static class ImportRequest {
        private TemplateManageFileDTO template;
        private Long pid = 0L;
        private String name;
        private Map<String, Long> datasetMapping = Map.of();
    }

    @PostMapping("/import")
    @Transactional(rollbackFor = Exception.class)
    public String importDashboard(@RequestBody ImportRequest input) throws Exception {
        guard.requireCreateIn(input.getPid());
        TemplateManageFileDTO pack = input.getTemplate();
        if (pack == null || !"dashboard".equals(pack.getDvType()) || input.getName() == null
                || input.getName().trim().isEmpty() || input.getName().trim().length() > 64) {
            DEException.throwException("请选择有效的仪表板应用模板，名称须为1至64个字符");
        }
        if (pack.getVersion() == null || pack.getVersion() < 2 || pack.getVersion() > 3
                || pack.getComponentData() == null || !json.readTree(pack.getComponentData()).isArray()
                || pack.getCanvasStyleData() == null || !json.readTree(pack.getCanvasStyleData()).isObject()
                || pack.getDynamicData() == null || !json.readTree(pack.getDynamicData()).isObject()) {
            DEException.throwException("模板格式或版本不支持");
        }
        VisualizationExport2AppVO app = null;
        if (pack.getAppData() != null && !pack.getAppData().isBlank()) {
            datasets.requireDatasetDesign();
            app = json.readValue(pack.getAppData(), VisualizationExport2AppVO.class);
            if (app.getDatasetGroupsInfo() == null || app.getDatasetTablesInfo() == null
                    || app.getDatasetTableFieldsInfo() == null || app.getVisualizationInfo() == null) {
                DEException.throwException("应用模板缺少数据集依赖信息");
            }
            for (var group : app.getDatasetGroupsInfo()) {
                Long targetId = input.getDatasetMapping() == null ? null : input.getDatasetMapping().get(group.getId().toString());
                var target = targetId == null ? null : datasetMapper.selectById(targetId);
                if (target == null || !"dataset".equals(target.getNodeType())) {
                    DEException.throwException("请为每个源数据集选择已存在的目标数据集");
                }
                group.setSystemDatasetId(targetId);
            }
            // Never create data sources, run their tasks, or reuse credentials from a migration file.
            app.setDatasourceTaskInfo(List.of());
            if (app.getDatasourceInfo() != null) app.getDatasourceInfo().forEach(source -> source.setConfiguration(null));
            if (app.getLinkages() == null) app.setLinkages(List.of());
            if (app.getLinkageFields() == null) app.setLinkageFields(List.of());
            if (app.getLinkJumps() == null) app.setLinkJumps(List.of());
            if (app.getLinkJumpInfos() == null) app.setLinkJumpInfos(List.of());
            if (app.getLinkJumpTargetInfos() == null) app.setLinkJumpTargetInfos(List.of());
        }
        var dynamic = json.readTree(pack.getDynamicData());
        dynamic.elements().forEachRemaining(chart -> { if (chart instanceof ObjectNode object) object.remove("data"); });
        DataVisualizationBaseRequest request = new DataVisualizationBaseRequest();
        request.setName(input.getName().trim());
        request.setPid(input.getPid());
        request.setType("dashboard");
        request.setNodeType("leaf");
        visualizations.nameCheck(request);
        request.setNewFrom("new_outer_template");
        request.setCanvasStyleData(pack.getCanvasStyleData());
        request.setComponentData(pack.getComponentData());
        request.setDynamicData(dynamic.toString());
        request.setStaticResource(pack.getStaticResource());
        request.setCalculationConfig(pack.getCalculationConfig());
        request.setVersion(pack.getVersion());
        request.setAppData(app);
        var unpacked = visualizations.decompression(request);
        request.setId(unpacked.getId());
        request.setComponentData(unpacked.getComponentData());
        request.setCanvasStyleData(unpacked.getCanvasStyleData());
        request.setCanvasViewInfo(unpacked.getCanvasViewInfo());
        request.setCalculationConfig(unpacked.getCalculationConfig());
        request.setAppData(unpacked.getAppData());
        request.setDataType("dataset");
        return visualizations.saveCanvas(request);
    }
}
