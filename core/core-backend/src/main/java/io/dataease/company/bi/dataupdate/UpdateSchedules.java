package io.dataease.company.bi.dataupdate;

import io.dataease.utils.CommonBeanFactory;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;

@Component
@Order(110)
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class UpdateSchedules implements ApplicationRunner {
    static final String GROUP = "COMPANY_DATA_UPDATE";
    private final Scheduler scheduler;
    private final UpdateStore store;
    private final UpdateRuntime runtime;
    private volatile boolean ready;
    public UpdateSchedules(Scheduler scheduler, UpdateStore store, UpdateRuntime runtime) { this.scheduler=scheduler; this.store=store; this.runtime=runtime; }
    @Override public void run(ApplicationArguments args) throws Exception { sync(); runtime.ready(); ready=true; }
    @Scheduled(fixedDelay=30000, initialDelay=30000)
    public void reconcile() {
        if (!ready) return;
        try { sync(); } catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Data update schedule reconciliation failed ({})", e.getClass().getSimpleName()); }
    }
    public synchronized void sync() throws SchedulerException {
        String group = group();
        Set<JobKey> expected = new HashSet<>();
        for (UpdateDefinition task : store.all()) {
            if (!task.enabled() || task.trigger()!=UpdateDefinition.Trigger.CRON || !runtime.owns(task) || store.grouped(task.id())) continue;
            JobKey jobKey = new JobKey(task.id(), group); TriggerKey triggerKey = new TriggerKey(task.id(), group); expected.add(jobKey);
            Trigger old = scheduler.getTrigger(triggerKey);
            if (old != null && Long.toString(task.version()).equals(old.getDescription())) continue;
            JobDetail job = JobBuilder.newJob(UpdateJob.class).withIdentity(jobKey).usingJobData("taskId", task.id()).storeDurably().build();
            scheduler.addJob(job, true);
            CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey).forJob(jobKey).withDescription(Long.toString(task.version()))
                    .startAt(Date.from(Instant.now().plusSeconds(1))).withSchedule(CronScheduleBuilder.cronSchedule(task.cron())
                            .inTimeZone(TimeZone.getTimeZone(ZoneId.of(task.timezone()))).withMisfireHandlingInstructionDoNothing()).build();
            if (old == null) scheduler.scheduleJob(trigger); else scheduler.rescheduleJob(triggerKey, trigger);
        }
        for (JobKey job : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))) if (!expected.contains(job)) scheduler.deleteJob(job);
    }
    String group() { return GROUP + "_" + UpdateStore.hash(Objects.toString(runtime.owner(), "unset")).substring(0,16); }
    public String next(String taskId) throws SchedulerException {
        Trigger trigger = scheduler.getTrigger(new TriggerKey(taskId, group()));
        return trigger == null || trigger.getNextFireTime() == null ? "" : trigger.getNextFireTime().toInstant().toString();
    }
    @DisallowConcurrentExecution
    public static class UpdateJob implements Job {
        @Override public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                UpdateRuntime runtime = CommonBeanFactory.getBean(UpdateRuntime.class);
                UpdateStore store = CommonBeanFactory.getBean(UpdateStore.class);
                UpdateDefinition task = store.get(context.getMergedJobDataMap().getString("taskId"));
                // A stale Quartz firing must not run a newly changed definition.
                if (!Long.toString(task.version()).equals(context.getTrigger().getDescription())) return;
                Instant planned = context.getScheduledFireTime().toInstant();
                runtime.submit(task, "CRON", planned, task.businessDate(planned), () -> { });
            } catch (Exception e) { throw new JobExecutionException("数据更新定时触发失败（" + e.getClass().getSimpleName() + "）", false); }
        }
    }
}
