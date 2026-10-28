package io.dataease.company.bi.etl;

import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.datasource.provider.CalciteProvider;
import io.dataease.extensions.datasource.dto.*;
import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlWiringTest {
    @Test void springCreatesAllComponentsWithoutLoginContext() {
        new ApplicationContextRunner()
                .withBean(javax.sql.DataSource.class,()->new DriverManagerDataSource("jdbc:h2:mem:etl_wiring;MODE=MySQL","sa",""))
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("dataSourceManage",mock(DataSourceManage.class));
                    context.getBeanFactory().registerSingleton("calciteProvider",mock(CalciteProvider.class));
                })
                .withBean(Scheduler.class,()->mock(Scheduler.class))
                .withBean(CompanyAdminGuard.class,()->mock(CompanyAdminGuard.class))
                .withBean(CompanyAuditRecorder.class,()->mock(CompanyAuditRecorder.class))
                .withUserConfiguration(EtlStore.class,EtlConnections.class,EtlSwitchService.class,EtlChainRunner.class,EtlQuartzRegistrar.class,EtlController.class)
                .run(context->{assertNull(context.getStartupFailure());assertNotNull(context.getBean(EtlController.class));});
    }
    @Test void connectionUsesDataEaseProviderAndCleansUpSshOnJdbcCloseFailure() throws Exception {
        var sources=mock(DataSourceManage.class);var provider=mock(CalciteProvider.class);
        DatasourceDTO dto=new DatasourceDTO();dto.setType("mysql");when(sources.getDs(123L)).thenReturn(dto);
        var object=new ConnectionObj();var jdbc=mock(java.sql.Connection.class);var session=mock(com.jcraft.jsch.Session.class);
        object.setConnection(jdbc);object.setSession(session);when(provider.getConnection(dto)).thenReturn(object);
        doThrow(new java.sql.SQLException("close failure")).when(jdbc).close();
        var handle=new EtlConnections(sources,provider).open(123L);
        assertThrows(java.sql.SQLException.class,handle::close);
        verify(sources).getDs(123L);verify(provider).getConnection(dto);verify(session).disconnect();
    }
    @Test void wrongInstanceDeletesPersistentJobsAndBlocksExecution() throws Exception {
        EtlStore store=mock(EtlStore.class);
        when(store.setting()).thenReturn(new EtlStore.Setting(true,"instance-old","03:00",true,1L,1L,null));
        var switches=spy(new EtlSwitchService(store,new MockEnvironment()));doReturn("instance-new").when(switches).instanceId();
        assertFalse(switches.effective());assertThrows(IllegalArgumentException.class,()->switches.allow("refresh",Instant.now()));
        Scheduler scheduler=mock(Scheduler.class);when(scheduler.getJobKeys(any())).thenReturn(Set.of(EtlQuartzRegistrar.JOB));when(scheduler.getTriggerKeys(any())).thenReturn(Set.of());
        new EtlQuartzRegistrar(scheduler,store,switches,mock(EtlChainRunner.class)).sync();
        verify(scheduler).deleteJob(EtlQuartzRegistrar.JOB);verify(scheduler,never()).scheduleJob(any(Trigger.class));
    }
    @Test void hardDisableBlocksTestsAsWellAsFormalRuns() {
        var switches=new EtlSwitchService(mock(EtlStore.class),new MockEnvironment().withProperty("COMPANY_BI_ETL_HARD_DISABLE","true"));
        assertThrows(IllegalArgumentException.class,()->switches.allow("refresh_test",Instant.parse("2026-10-28T04:00:00Z")));
        assertThrows(IllegalArgumentException.class,()->switches.allow("refresh",Instant.parse("2026-10-28T04:00:00Z")));
    }
    @Test void adminGuardRunsBeforeReadingOrMutatingAnything() {
        var guard=mock(CompanyAdminGuard.class);var store=mock(EtlStore.class);
        when(guard.require(CompanyCapability.ETL_ACCESS)).thenThrow(new IllegalStateException("denied"));
        var controller=new EtlController(guard,mock(CompanyAuditRecorder.class),store,mock(EtlConnections.class),mock(EtlSwitchService.class),mock(EtlChainRunner.class),mock(EtlQuartzRegistrar.class));
        assertThrows(IllegalStateException.class,controller::packages);
        assertThrows(IllegalStateException.class,()->controller.activate(1,null));
        verifyNoInteractions(store);
    }
    @Test void ramJobStoreDoesNotDeliverMissedCronFire() throws Exception {
        Properties properties=new Properties();properties.put("org.quartz.scheduler.instanceName","etl-misfire-test");properties.put("org.quartz.threadPool.threadCount","1");
        properties.put("org.quartz.jobStore.class","org.quartz.simpl.RAMJobStore");properties.put("org.quartz.jobStore.misfireThreshold","1");
        Scheduler scheduler=new org.quartz.impl.StdSchedulerFactory(properties).getScheduler();
        try {
            Instant now=Instant.now();
            var job=JobBuilder.newJob(CountingJob.class).withIdentity("test").build();
            var trigger=(org.quartz.impl.triggers.CronTriggerImpl)TriggerBuilder.newTrigger().withIdentity("test")
                    .startAt(java.util.Date.from(now.minus(Duration.ofDays(2))))
                    .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(now.atZone(EtlModel.ZONE).getHour(),now.atZone(EtlModel.ZONE).getMinute())
                            .inTimeZone(TimeZone.getTimeZone(EtlModel.ZONE)).withMisfireHandlingInstructionDoNothing()).build();
            scheduler.scheduleJob(job,trigger);CountingJob.calls.set(0);scheduler.start();
            // Wait for the RAM store to process the stale trigger, bounded to one second.
            for(int i=0;i<20;i++) { var next=scheduler.getTrigger(trigger.getKey()).getNextFireTime();if(next!=null && next.toInstant().isAfter(now)) break;Thread.sleep(50); }
            assertEquals(0,CountingJob.calls.get());assertTrue(scheduler.getTrigger(trigger.getKey()).getNextFireTime().toInstant().isAfter(now));
        } finally {scheduler.shutdown(true);}
    }
    public static class CountingJob implements Job {
        static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        public void execute(JobExecutionContext context){calls.incrementAndGet();}
    }
}
