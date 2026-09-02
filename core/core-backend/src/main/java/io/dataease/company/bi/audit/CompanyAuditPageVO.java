package io.dataease.company.bi.audit;

import java.util.ArrayList;
import java.util.List;

public class CompanyAuditPageVO {

    private long total;
    private int page;
    private int pageSize;
    private List<CompanyAuditLogVO> records = new ArrayList<>();

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<CompanyAuditLogVO> getRecords() {
        return records;
    }

    public void setRecords(List<CompanyAuditLogVO> records) {
        this.records = records == null ? new ArrayList<>() : records;
    }
}
