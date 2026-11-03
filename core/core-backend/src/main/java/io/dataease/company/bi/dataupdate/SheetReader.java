package io.dataease.company.bi.dataupdate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.feishu.CompanyFeishuWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class SheetReader {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final CompanyFeishuWorker worker;
    public SheetReader(CompanyFeishuWorker worker) { this.worker=worker; }
    private Map<String,Object> call(String method,Map<String,Object> args) {
        try { return worker.call(method,args); }
        catch(RuntimeException e) { throw new UpdateDefinition.ValidationException(Objects.toString(e.getMessage(),"飞书读取失败，请检查应用连接和表格授权")); }
    }
    public Map<String,Object> list(String connectionKey,String url) {
        return call("sheets.list",Map.of("connectionKey",connectionKey,"url",url));
    }
    public record Page(List<List<Object>> rows,int sheetRows,int columns) { }
    public Page read(SheetSource source,int start,int end) {
        JsonNode body=JSON.valueToTree(call("sheets.read",Map.of("connectionKey",source.connectionKey(),"url",source.url(),
                "sheetId",source.sheetId(),"columnRange",Objects.toString(source.columnRange(),""),"startRow",start,"endRow",end)));
        List<List<Object>> rows=new ArrayList<>();
        for(JsonNode row:body.path("values")) {
            List<Object> cells=new ArrayList<>();
            for(JsonNode cell:row) {
                require(!cell.isContainerNode(),"表格包含不支持的富文本或复杂单元格，请转换为普通值");
                String text=cell.isNull()?null:cell.asText();
                require(text==null || text.length()<=16384,"单元格超过 16384 字符");
                // Textual decimal representation avoids a second floating-point conversion in Java.
                cells.add(text);
            }
            rows.add(cells);
        }
        int first=body.path("columns").path("start").asInt(), last=body.path("columns").path("end").asInt();
        require(first>0 && last>=first && last-first<256,"飞书列范围无效");
        return new Page(rows,body.path("sheet").path("rowCount").asInt(),last-first+1);
    }
    public List<String> headers(SheetSource source) {
        Page page=read(source,source.headerRow(),source.headerRow());
        require(!page.rows().isEmpty(),"表头行为空");
        List<String> result=new ArrayList<>(); Set<String> names=new HashSet<>();
        int width=page.columns();
        if(source.columnRange()==null || source.columnRange().isBlank()) {
            var header=page.rows().getFirst();
            while(width>0 && (width>header.size() || Objects.toString(header.get(width-1),"").isBlank()))width--;
        }
        require(width>0,"表头行为空");
        for(int i=0;i<width;i++) {
            String value=i<page.rows().getFirst().size()?Objects.toString(page.rows().getFirst().get(i),"").strip():"";
            require(!value.isBlank(),"第 "+(i+1)+" 列表头为空，请缩小列范围或补充表头");
            require(names.add(value),"表头重复："+value); result.add(value);
        }
        return result;
    }
}
