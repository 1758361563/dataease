package io.dataease.company.bi.dataupdate;

import java.net.URI;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

/** Immutable spreadsheet configuration pinned into each update run and group snapshot. */
public record SheetSource(String sourceId, String name, String connectionKey, String url,
        String sheetId, String sheetName, int headerRow, int dataStartRow, Integer dataEndRow,
        String columnRange, List<Field> fields, List<String> nullValues) {
    public record Field(String source, String target, boolean required, String dateFormat) { }
    public SheetSource withId(String id) {
        return new SheetSource(id,name,connectionKey,url,sheetId,sheetName,headerRow,dataStartRow,dataEndRow,columnRange,fields,nullValues);
    }
    public void validate(boolean mapping) {
        require(sourceId == null || sourceId.matches("[0-9]{1,19}"), "来源 ID 无效");
        require(name != null && !name.isBlank() && name.length() <= 128,"请填写来源名称（最多 128 字）");
        require(connectionKey != null && connectionKey.matches("[a-zA-Z0-9_-]{1,64}"),"请选择飞书应用连接");
        try {
            URI uri=URI.create(url);
            require("https".equals(uri.getScheme()) && uri.getHost()!=null && (uri.getHost().equals("feishu.cn") || uri.getHost().endsWith(".feishu.cn"))
                    && uri.getPort()==-1 && uri.getUserInfo()==null && uri.getPath().matches("/sheets/[a-zA-Z0-9]+/?") && url.length()<=512,
                    "请输入飞书 /sheets/ 电子表格地址");
        } catch (Exception e) { throw new UpdateDefinition.ValidationException("请输入飞书 /sheets/ 电子表格地址"); }
        require(sheetId != null && sheetId.matches("[a-zA-Z0-9_-]{1,64}"),"请选择工作表");
        require(sheetName!=null && !sheetName.isBlank() && sheetName.length()<=128,"工作表名称无效");
        require(headerRow>=1 && headerRow<=1_000_000 && dataStartRow>headerRow && dataStartRow<=1_000_000,"数据起始行须在表头行之后");
        require(dataEndRow==null || dataEndRow>=dataStartRow && dataEndRow<=1_000_000,"数据结束行无效");
        require(columnRange==null || columnRange.isBlank() || columnRange.matches("[A-Z]{1,3}:[A-Z]{1,3}"),"列范围应为 A:M 格式");
        require(nullValues!=null && nullValues.size()<=20 && nullValues.stream().allMatch(v->v!=null && v.length()<=64),"空值标记无效");
        if (!mapping) return;
        require(fields!=null && !fields.isEmpty() && fields.size()<=256,"请配置字段映射（最多 256 列）");
        Set<String> sources=new HashSet<>(), targets=new HashSet<>();
        for(Field f:fields) {
            require(f!=null,"字段映射无效"); UpdateDefinition.identifier(f.source()); UpdateDefinition.identifier(f.target());
            require(sources.add(f.source()) && targets.add(f.target()),"来源列和目标列不能重复映射");
            require(f.dateFormat()==null || Set.of("","yyyy-MM-dd","yyyy/MM/dd","yyyyMMdd","yyyy-MM-dd HH:mm:ss","yyyy/MM/dd HH:mm:ss").contains(f.dateFormat()),"日期格式不支持");
        }
    }
}
