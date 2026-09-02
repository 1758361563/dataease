package io.dataease.company.bi.admin.acl;

import java.util.ArrayList;
import java.util.List;

public class CompanyAdminCatalogVO {

    private List<CompanyCatalogNodeVO> dashboards = new ArrayList<>();
    private List<CompanyCatalogNodeVO> datasets = new ArrayList<>();

    public List<CompanyCatalogNodeVO> getDashboards() {
        return dashboards;
    }

    public void setDashboards(List<CompanyCatalogNodeVO> dashboards) {
        this.dashboards = dashboards == null ? new ArrayList<>() : dashboards;
    }

    public List<CompanyCatalogNodeVO> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<CompanyCatalogNodeVO> datasets) {
        this.datasets = datasets == null ? new ArrayList<>() : datasets;
    }
}
