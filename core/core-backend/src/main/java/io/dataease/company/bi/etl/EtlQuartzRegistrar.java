package io.dataease.company.bi.etl;

import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.etl.EtlModel.*;

@Component
@Order(100)
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class EtlQuartzRegistrar implements ApplicationRunner {
    public static final String GROUP="COMPANY_ETL";
    public static final JobKey JOB=new JobKey("refresh",GROUP);
    public static final TriggerKey TRIGGER=new TriggerKey("daily",GROUP);
    private final Scheduler scheduler;
    private final EtlStore store;
    private final EtlSwitchService switches;
    private final EtlChainRunner runner;
    private volatile boolean ready;
    public EtlQuartzRegistrar(Scheduler scheduler,EtlStore store,EtlSwitchService switches,EtlChainRunner runner) {
        this.scheduler=scheduler;this.store=store;this.switches=switches;this.runner=runner;
    }
    public boolean ready() { return ready; }
    @Override public void run(ApplicationArguments args) throws Exception {
        store.initialize();sync();ready=true;runner.resumeOnStartup();
        if (!ZoneId.systemDefault().equals(ZONE)) io.dataease.utils.LogUtil.warn("ETL: JVM 时区不是 Asia/Shanghai，刷新窗口仍按上海时间计算");
    }
    public static CronTrigger trigger(String daily, Instant now) {
        LocalTime at=LocalTime.parse(daily);
        return TriggerBuilder.newTrigger().withIdentity(TRIGGER).forJob(JOB).startAt(Date.from(now.plusSeconds(1)))
                .withSchedule(CronScheduleBuilder.cronSchedule("0 " + at.getMinute() + " " + at.getHour() + " * * ?")
                        .inTimeZone(TimeZone.getTimeZone(ZONE)).withMisfireHandlingInstructionDoNothing()).build();
    }
    public synchronized void sync() throws SchedulerException {
        if (!switches.effective() || !store.setting().chainEnabled()) {
            for (JobKey key:scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))) scheduler.deleteJob(key);
            for (TriggerKey key:scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(GROUP))) scheduler.unscheduleJob(key);
            return;
        }
        JobDetail job=JobBuilder.newJob(EtlQuartzJob.class).withIdentity(JOB).storeDurably().build();
        scheduler.addJob(job,true);
        CronTrigger trigger=trigger(store.setting().dailyAt(),Instant.now());
        if (scheduler.checkExists(TRIGGER)) scheduler.rescheduleJob(TRIGGER,trigger); else scheduler.scheduleJob(trigger);
    }
    public String next() throws SchedulerException {
        Trigger trigger=scheduler.getTrigger(TRIGGER);
        return trigger==null || trigger.getNextFireTime()==null ? "" : time(trigger.getNextFireTime().toInstant());
    }
}
