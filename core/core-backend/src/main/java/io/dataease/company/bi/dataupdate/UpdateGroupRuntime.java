package io.dataease.company.bi.dataupdate;

import io.dataease.utils.CommonBeanFactory;
import jakarta.annotation.PreDestroy;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@Order(120)
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class UpdateGroupRuntime implements ApplicationRunner {
    private final UpdateGroups groups;private final UpdateRuntime runtime;private final UpdateExecutor executor;
    private final DataSource metadata;private final Scheduler scheduler;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("data-update-group").factory());
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("data-update-group-monitor").factory());
    private final Set<String> active=ConcurrentHashMap.newKeySet();private volatile UpdateExecution execution;private volatile boolean ready;
    public UpdateGroupRuntime(UpdateGroups groups,UpdateRuntime runtime,UpdateExecutor executor,DataSource metadata,Scheduler scheduler){this.groups=groups;this.runtime=runtime;this.executor=executor;this.metadata=metadata;this.scheduler=scheduler;}
    @Override public void run(ApplicationArguments args)throws Exception{sync();ready=true;}
    public String submit(String id,long version,String date,String trigger,java.util.function.Consumer<String> audit){require(ready&&!runtime.disabled(),"任务组执行未就绪或已被运维关闭");return groups.submit(id,version,date,trigger,runtime.owner(),audit);}
    @Scheduled(fixedDelay=5000,initialDelay=5000)public void tick(){
        if(!ready)return;
        try{
            if(runtime.disabled()){if(execution!=null)execution.stop("运维已停止执行");return;}
            for(var pending:groups.pending(runtime.owner())) {
                if(active.contains(pending.id()))continue;
                if("RUNNING".equals(pending.status())) {
                    try(var lock=UpdateLock.acquireMany(metadata,keys(pending.snapshot()))) {
                        if(lock!=null)groups.finish(pending.id(),"NEEDS_REVIEW","执行进程中断；确认旧执行已结束后核查并释放占用",false);
                    }
                }else if(active.isEmpty()&&active.add(pending.id()))worker.submit(()->execute(pending));
            }
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Group dispatch failed ({})",e.getClass().getSimpleName());}
    }
    private static List<String> keys(UpdateGroups.Snapshot snapshot){return snapshot.tasks().stream().map(UpdateStore::targetKey).toList();}
    void execute(UpdateGroups.Pending pending){
        String id=pending.id();
        try(var lock=UpdateLock.acquireMany(metadata,keys(pending.snapshot()))) {
            if(lock==null)return;
            if(!groups.start(id))return;
            for(var task:pending.snapshot().tasks()) {
                require(lock.alive(),"批次执行锁已丢失");
                if(groups.cancelled(id)||runtime.disabled()){groups.finish(id,"CANCELLED","批次已取消；已完成输出保留",true);return;}
                execution=new UpdateExecution(task.timeoutSeconds());UpdateExecution current=execution;
                current.commitGuard(()->lock.alive()&&!groups.cancelled(id)&&!runtime.disabled());
                ScheduledFuture<?> watch=monitor.scheduleWithFixedDelay(()->{
                    try{if(!lock.alive())current.stop("批次执行锁丢失");else if(groups.cancelled(id)||runtime.disabled())current.stop("批次已取消");current.check();}
                    catch(Exception e){current.stop("批次执行监控中断");}
                },0,1,TimeUnit.SECONDS);
                try{
                    groups.node(id,task.id(),"RUNNING",0,"");
                    long rows=executor.execute(task,LocalDate.parse(pending.date()),current);
                    groups.node(id,task.id(),"SUCCESS",rows,"更新完成");
                }catch(Exception e){
                    String message=current.stopped()?current.reason():UpdateRuntime.error(e);
                    groups.node(id,task.id(),current.stopped()?"CANCELLED":"FAILED",0,message);
                    boolean held=lock.alive();groups.finish(id,held?(current.stopped()?"CANCELLED":"FAILED"):"NEEDS_REVIEW",message+"；后续节点已阻断，已成功的输出单独保留",held);return;
                }finally{watch.cancel(false);execution=null;}
            }
            groups.finish(id,"SUCCESS","本批全部完成",true);
        }catch(Exception e){
            try{groups.finish(id,"NEEDS_REVIEW","执行状态需要核查；"+UpdateRuntime.error(e),false);}catch(Exception ignored){}
        }finally{execution=null;active.remove(id);}
    }
    public void reconcile(String run,Runnable audit)throws Exception {
        var detail=groups.detail(run);require(Set.of("NEEDS_REVIEW","RUNNING").contains(detail.get("status")),"仅可核查运行中或中断批次");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var snapshot=mapper.readValue((String)detail.get("snapshot"),UpdateGroups.Snapshot.class);
        try(var lock=UpdateLock.acquireMany(metadata,keys(snapshot))){require(lock!=null,"仍有执行进程占用目标表");require(Set.of("NEEDS_REVIEW","RUNNING").contains(groups.detail(run).get("status")),"批次状态已变化，请刷新记录");groups.tx.executeWithoutResult(status->{audit.run();groups.finish(run,"INTERRUPTED","用户确认旧执行结束并解除占用；请新建批次重跑",true);});}
    }
    private String group(){return "COMPANY_UPDATE_GROUP_"+UpdateStore.hash(Objects.toString(runtime.owner(),"unset")).substring(0,16);}
    @Scheduled(fixedDelay=30000,initialDelay=30000)public void reconcileSchedules(){if(ready)try{sync();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Group schedules failed ({})",e.getClass().getSimpleName());}}
    private synchronized void sync()throws SchedulerException {
        Set<JobKey> expected=new HashSet<>();
        for(var definition:groups.all()) {
            if(runtime.disabled()||!definition.enabled()||runtime.owner()==null||!runtime.owner().equals(definition.owner()))continue;
            JobKey job=new JobKey(definition.id(),group());TriggerKey key=new TriggerKey(definition.id(),group());expected.add(job);
            Trigger previous=scheduler.getTrigger(key);if(previous!=null&&Long.toString(definition.version()).equals(previous.getDescription()))continue;
            scheduler.addJob(JobBuilder.newJob(GroupJob.class).withIdentity(job).usingJobData("groupId",definition.id()).storeDurably().build(),true);
            var trigger=TriggerBuilder.newTrigger().withIdentity(key).forJob(job).withDescription(Long.toString(definition.version())).startAt(Date.from(Instant.now().plusSeconds(1)))
                    .withSchedule(CronScheduleBuilder.cronSchedule(definition.cron()).inTimeZone(TimeZone.getTimeZone(ZoneId.of(definition.timezone()))).withMisfireHandlingInstructionDoNothing()).build();
            if(previous==null)scheduler.scheduleJob(trigger);else scheduler.rescheduleJob(key,trigger);
        }
        for(JobKey key:scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group())))if(!expected.contains(key))scheduler.deleteJob(key);
    }
    @DisallowConcurrentExecution public static class GroupJob implements Job {
        public void execute(JobExecutionContext context)throws JobExecutionException {
            try{
                var groups=CommonBeanFactory.getBean(UpdateGroups.class);var runner=CommonBeanFactory.getBean(UpdateGroupRuntime.class);var runtime=CommonBeanFactory.getBean(UpdateRuntime.class);
                var definition=groups.get(context.getMergedJobDataMap().getString("groupId"));
                if(!definition.enabled()||!Objects.equals(definition.owner(),runtime.owner())||!Long.toString(definition.version()).equals(context.getTrigger().getDescription()))return;
                Instant at=context.getScheduledFireTime().toInstant();String date=at.atZone(ZoneId.of(definition.timezone())).toLocalDate().toString();
                String trigger="cron:"+definition.version()+":"+at;
                try{runner.submit(definition.id(),definition.version(),date,trigger,runId->{});}
                catch(UpdateDefinition.ValidationException e){groups.skipped(definition,date,trigger,runtime.owner(),e.getMessage());}
            }catch(Exception e){throw new JobExecutionException("任务组触发失败（"+e.getClass().getSimpleName()+"）",false);}
        }
    }
    @PreDestroy public void close(){ready=false;if(execution!=null)execution.stop("服务关闭");worker.shutdownNow();monitor.shutdownNow();}
}
