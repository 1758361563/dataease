package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.admin.*;
import io.dataease.company.bi.audit.*;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/company/etl/data-update/setup")
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class UpdateSetupController {
    private final CompanyAdminGuard guard;private final CompanyAuditRecorder audit;
    private final UpdateDdlService ddl;private final UpdateInspection inspection;private final UpdateRuntime runtime;
    public UpdateSetupController(CompanyAdminGuard guard,CompanyAuditRecorder audit,UpdateDdlService ddl,UpdateInspection inspection,UpdateRuntime runtime) {this.guard=guard;this.audit=audit;this.ddl=ddl;this.inspection=inspection;this.runtime=runtime;}
    private long admin(){return guard.require(CompanyCapability.ETL_ACCESS);}
    private void record(HttpServletRequest request,long user,String id,String action){audit.record(CompanyAdminAuditContext.from(request,user),CompanyAuditActionType.DATA_UPDATE_SAVE,"DATA_UPDATE_DDL",null,null,UpdateStore.json(Map.of("operationId",id,"action",action)));}
    @PostMapping("/ddl/check") public Object check(@RequestBody UpdateDdlService.Request body)throws Exception{admin();return ddl.check(body);}
    @PostMapping("/ddl") public Object create(@RequestBody UpdateDdlService.Request body,HttpServletRequest request)throws Exception{long user=admin();UpdateDefinition.require(!runtime.disabled(),"运维已关闭数据更新执行");return ddl.create(body,()->record(request,user,body.operationId(),"CREATE"));}
    @GetMapping("/ddl") public Object list(@RequestParam String datasourceId){admin();return ddl.list(datasourceId);}
    @GetMapping("/ddl/{id}") public Object operation(@PathVariable String id){admin();return ddl.get(id);}
    @PostMapping("/ddl/{id}/reconcile") public Object reconcile(@PathVariable String id,HttpServletRequest request)throws Exception{long user=admin();return ddl.reconcile(id,()->record(request,user,id,"RECONCILE"));}
    @GetMapping("/structure") public Object structure(@RequestParam String datasourceId,@RequestParam String table)throws Exception{admin();return inspection.structure(datasourceId,table);}
    @PostMapping("/inspect") public Object inspect(@RequestBody UpdateDefinition body,@RequestParam(defaultValue="false")boolean preview)throws Exception{admin();return inspection.inspect(body,preview);}
}
