package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.admin.*;
import io.dataease.company.bi.audit.*;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/company/etl/data-update/groups")
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class UpdateGroupController {
    private final CompanyAdminGuard guard;private final CompanyAuditRecorder audit;private final UpdateGroups groups;private final UpdateGroupRuntime runner;private final UpdateRuntime runtime;
    public UpdateGroupController(CompanyAdminGuard guard,CompanyAuditRecorder audit,UpdateGroups groups,UpdateGroupRuntime runner,UpdateRuntime runtime){this.guard=guard;this.audit=audit;this.groups=groups;this.runner=runner;this.runtime=runtime;}
    private long admin(){return guard.require(CompanyCapability.ETL_ACCESS);}
    private void record(HttpServletRequest request,long user,String id,String action){audit.record(CompanyAdminAuditContext.from(request,user),CompanyAuditActionType.DATA_UPDATE_SAVE,"DATA_UPDATE_GROUP",null,null,UpdateStore.json(Map.of("id",id,"action",action)));}
    @GetMapping public Object list(){admin();return groups.all();}
    @PostMapping public Object save(@RequestBody UpdateGroups.Definition body,HttpServletRequest request){long user=admin();return groups.save(body,runtime.owner(),saved->record(request,user,saved.id(),"SAVE"));}
    public record Request(long version,String businessDate){}
    @PostMapping("/{id}/run") public Object run(@PathVariable String id,@RequestBody Request body,HttpServletRequest request){long user=admin();var group=groups.get(id);String date=UpdateDefinition.clean(body.businessDate()).isEmpty()?LocalDate.now(ZoneId.of(group.timezone())).toString():body.businessDate();return Map.of("id",runner.submit(id,body.version(),date,"manual:"+UUID.randomUUID(),runId->record(request,user,runId,"RUN")));}
    @GetMapping("/{id}/runs") public Object runs(@PathVariable String id){admin();return groups.history(id);}
    @GetMapping("/runs/{id}") public Object detail(@PathVariable String id){admin();return groups.detail(id);}
    @PostMapping("/runs/{id}/cancel")public void cancel(@PathVariable String id,HttpServletRequest request){long user=admin();groups.cancel(id,()->record(request,user,id,"CANCEL"));}
    @PostMapping("/runs/{id}/reconcile")public void reconcile(@PathVariable String id,HttpServletRequest request)throws Exception{long user=admin();runner.reconcile(id,()->record(request,user,id,"RECONCILE"));}
    @DeleteMapping("/{id}")public void delete(@PathVariable String id,@RequestParam long version,HttpServletRequest request){long user=admin();groups.delete(id,version,()->record(request,user,id,"DELETE"));}
}
