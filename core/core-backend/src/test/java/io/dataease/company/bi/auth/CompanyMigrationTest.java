package io.dataease.company.bi.auth;

import io.dataease.api.template.dto.TemplateManageFileDTO;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.api.visualization.vo.DataVisualizationVO;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.company.bi.permission.CompanyDatasetDesignGuard;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.exception.DEException;
import io.dataease.visualization.server.DataVisualizationServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanyMigrationTest {
    private final DataVisualizationServer visualizations = mock(DataVisualizationServer.class);
    private final CompanyDashboardGuard guard = mock(CompanyDashboardGuard.class);
    private final CompanyDatasetDesignGuard datasets = mock(CompanyDatasetDesignGuard.class);
    private final CoreDatasetGroupMapper mapper = mock(CoreDatasetGroupMapper.class);
    private final CompanyDashboardMigrationServer server = new CompanyDashboardMigrationServer(visualizations, guard, datasets, mapper);

    private CompanyDashboardMigrationServer.ImportRequest request() {
        var pack = new TemplateManageFileDTO();
        pack.setDvType("dashboard"); pack.setVersion(3); pack.setComponentData("[]");
        pack.setCanvasStyleData("{}"); pack.setDynamicData("{}");
        var input = new CompanyDashboardMigrationServer.ImportRequest();
        input.setTemplate(pack); input.setName("Imported test");
        return input;
    }

    @Test void requiresDestinationPermissionBeforeAnyWrite() {
        doThrow(new IllegalStateException("denied")).when(guard).requireCreateIn(any());
        assertThrows(IllegalStateException.class, () -> server.importDashboard(request()));
        verifyNoInteractions(visualizations, mapper);
    }

    @Test void malformedPackageDoesNotWrite() {
        var input = request(); input.getTemplate().setComponentData("{}");
        assertThrows(DEException.class, () -> server.importDashboard(input));
        verifyNoInteractions(visualizations, mapper);
    }

    @Test void duplicateNameDoesNotUnpack() throws Exception {
        doThrow(new IllegalStateException("duplicate")).when(visualizations).nameCheck(any());
        assertThrows(IllegalStateException.class, () -> server.importDashboard(request()));
        verify(visualizations, never()).decompression(any());
        verify(visualizations, never()).saveCanvas(any());
    }

    @Test void packageCannotSelectTargetDatasetImplicitly() {
        var input = request();
        input.getTemplate().setAppData("{\"datasetGroupsInfo\":[{\"id\":\"7\",\"systemDatasetId\":\"1\"}],\"datasetTablesInfo\":[],\"datasetTableFieldsInfo\":[],\"visualizationInfo\":\"{}\"}");
        assertThrows(DEException.class, () -> server.importDashboard(input));
        verify(datasets).requireDatasetDesign();
        verifyNoInteractions(visualizations);
    }

    @Test void draftUsesFreshUnpackedIdsAndNeverDatasourceCreation() throws Exception {
        var unpacked = new DataVisualizationVO(); unpacked.setId(100L);
        unpacked.setComponentData("[]"); unpacked.setCanvasStyleData("{}");
        when(visualizations.decompression(any())).thenReturn(unpacked);
        when(visualizations.saveCanvas(any())).thenAnswer(call -> {
            DataVisualizationBaseRequest saved = call.getArgument(0);
            assertEquals(100L, saved.getId()); assertEquals("dataset", saved.getDataType());
            assertEquals("dashboard", saved.getType()); assertEquals("leaf", saved.getNodeType());
            assertEquals("Imported test", saved.getName()); assertEquals(0L, saved.getPid());
            return "100";
        });
        assertEquals("100", server.importDashboard(request()));
        verify(guard).requireCreateIn(0L);
    }

    @Test void acceptsExportFormMetadataWithoutReusingConnectionConfiguration() throws Exception {
        var input = request();
        input.getTemplate().setAppData("{\"appName\":\"test\",\"creator\":\"test\",\"datasetGroupsInfo\":[],\"datasetTablesInfo\":[],\"datasetTableFieldsInfo\":[],\"visualizationInfo\":\"{}\",\"datasourceInfo\":[{\"configuration\":\"must be discarded\"}]}");
        var unpacked = new DataVisualizationVO(); unpacked.setId(100L);
        when(visualizations.decompression(any())).thenAnswer(call -> {
            DataVisualizationBaseRequest request = call.getArgument(0);
            assertNull(request.getAppData().getDatasourceInfo().getFirst().getConfiguration());
            assertTrue(request.getAppData().getDatasourceTaskInfo().isEmpty());
            return unpacked;
        });
        when(visualizations.saveCanvas(any())).thenReturn("100");
        assertEquals("100", server.importDashboard(input));
    }
}
