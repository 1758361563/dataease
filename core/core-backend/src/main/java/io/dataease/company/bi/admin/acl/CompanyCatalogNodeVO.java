package io.dataease.company.bi.admin.acl;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.ArrayList;
import java.util.List;

public class CompanyCatalogNodeVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String name;
    private String type;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long pid;
    private boolean leaf;
    private List<CompanyCatalogNodeVO> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getPid() {
        return pid;
    }

    public void setPid(Long pid) {
        this.pid = pid;
    }

    public boolean isLeaf() {
        return leaf;
    }

    public void setLeaf(boolean leaf) {
        this.leaf = leaf;
    }

    public List<CompanyCatalogNodeVO> getChildren() {
        return children;
    }

    public void setChildren(List<CompanyCatalogNodeVO> children) {
        this.children = children == null ? new ArrayList<>() : children;
    }
}
