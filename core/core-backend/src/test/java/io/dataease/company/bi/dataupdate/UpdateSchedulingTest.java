package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.etl.EtlSwitchService;
import io.dataease.company.bi.permission.CompanyCapability;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateSchedulingTest {
    static final Instant PLANNED=Instant.parse("2026-10-29T19:00:00Z");
    static final LocalDate DATE=LocalDate.of(2026,10,30);
    static UpdateDefinition scheduled(String owner) {
        var task=UpdateCoreTest.task("a",List.of("upstream"));
        return new UpdateDefinition(task.id(),task.version(),task.datasourceId(),task.schema(),task.table(),task.mode(),task.sourceDatasourceId(),task.sql(),task.strategy(),task.keys(),task.windowColumn(),task.windowDays(),task.allowEmpty(),UpdateDefinition.Trigger.CRON,true,task.cron(),task.timezone(),task.dependencies(),task.timeoutSeconds(),task.maxRows(),owner);
    }
    static class Fixture implements AutoCloseable {
        final UpdateStore store=mock(UpdateStore.class);
        final EtlSwitchService identity=mock(EtlSwitchService.class);
        final Environment env=mock(Environment.class);
        final UpdateRuntime runtime=new UpdateRuntime(store,mock(UpdateExecutor.class),mock(DataSource.class),identity,env);
        Fixture() { when(identity.instanceId()).thenReturn("local"); runtime.ready(); }
        public void close() { runtime.close(); }
    }
    @Test void copiedInstanceCannotAutomaticallyRun() {
        try(var f=new Fixture()) {
            assertThrows(IllegalArgumentException.class,()->f.runtime.submit(scheduled("other"),"CRON",PLANNED,DATE,()->{}));
            verifyNoInteractions(f.store);
        }
    }
    @Test void disabledRuntimeRejectsManualRuns() {
        try(var f=new Fixture()) {
            when(f.env.getProperty("COMPANY_BI_DATA_UPDATE_HARD_DISABLE","false")).thenReturn("true");
            assertThrows(IllegalArgumentException.class,()->f.runtime.submit(scheduled("local"),"MANUAL",PLANNED,DATE,()->{}));
            verifyNoInteractions(f.store);
        }
    }
    @Test void missingUpstreamProducesVisibleSkippedCronRun() {
        try(var f=new Fixture()) {
            var task=scheduled("local"); when(f.store.upstreamRuns(task,DATE)).thenReturn(null);
            when(f.store.skipped(eq(task),anyString(),eq(DATE),anyString())).thenReturn("skipped");
            assertEquals("skipped",f.runtime.submit(task,"CRON",PLANNED,DATE,()->{}));
            verify(f.store,never()).enqueue(any(),anyString(),anyString(),any(),anyMap(),any());
        }
    }
    @Test void manualRunsRequireSuccessfulUpstreams() {
        try(var f=new Fixture()) {
            when(f.store.upstreamRuns(scheduled("local"),DATE)).thenReturn(null);
            assertThrows(IllegalArgumentException.class,()->f.runtime.submit(scheduled("local"),"MANUAL",PLANNED,DATE,()->{}));
            verify(f.store,never()).skipped(any(),anyString(),any(),anyString());
        }
    }
    @Test void cronDeduplicationKeyUsesScheduledTimeAndVersion() {
        try(var f=new Fixture()) {
            var task=scheduled("local"); when(f.store.upstreamRuns(task,DATE)).thenReturn(Map.of("upstream","run-1"));
            f.runtime.submit(task,"CRON",PLANNED,DATE,()->{});
            verify(f.store).enqueue(eq(task),eq("CRON"),eq("cron:1:"+PLANNED),eq(DATE),eq(Map.of("upstream","run-1")),any());
        }
    }
    @Test void busyCronIsRecordedInsteadOfRetried() {
        try(var f=new Fixture()) {
            var task=scheduled("local"); when(f.store.upstreamRuns(task,DATE)).thenReturn(Map.of());
            when(f.store.enqueue(any(),anyString(),anyString(),any(),anyMap(),any())).thenThrow(new IllegalArgumentException("已有运行"));
            f.runtime.submit(task,"CRON",PLANNED,DATE,()->{});
            verify(f.store).skipped(eq(task),anyString(),eq(DATE),eq("已有运行"));
        }
    }
    @Test void dependencyKeyPinsUpstreamRunCombination() {
        try(var f=new Fixture()) {
            var task=scheduled("local"); when(f.store.upstreamRuns(task,DATE)).thenReturn(Map.of("upstream","run-2"));
            f.runtime.submit(task,"DEPENDENCY",PLANNED,DATE,()->{});
            verify(f.store).enqueue(eq(task),eq("DEPENDENCY"),eq("dependency:1:2026-10-30:{\"upstream\":\"run-2\"}"),eq(DATE),anyMap(),any());
        }
    }
    @Test void quartzUsesBusinessTimezoneAndSkipsMissedFirings() throws Exception {
        Scheduler scheduler=mock(Scheduler.class); UpdateStore store=mock(UpdateStore.class); UpdateRuntime runtime=mock(UpdateRuntime.class);
        var task=scheduled("local"); when(runtime.owner()).thenReturn("local"); when(runtime.owns(task)).thenReturn(true); when(store.all()).thenReturn(List.of(task));
        when(scheduler.getJobKeys(any())).thenReturn(Set.of());
        UpdateSchedules schedules=new UpdateSchedules(scheduler,store,runtime); schedules.sync();
        ArgumentCaptor<Trigger> capture=ArgumentCaptor.forClass(Trigger.class); verify(scheduler).scheduleJob(capture.capture());
        CronTrigger trigger=(CronTrigger)capture.getValue();
        assertEquals("Asia/Shanghai",trigger.getTimeZone().getID()); assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,trigger.getMisfireInstruction());
        assertEquals("1",trigger.getDescription());
    }
    @Test void schedulerGroupsAreIsolatedByInstance() {
        UpdateRuntime first=mock(UpdateRuntime.class),second=mock(UpdateRuntime.class); when(first.owner()).thenReturn("first"); when(second.owner()).thenReturn("second");
        assertNotEquals(new UpdateSchedules(null,null,first).group(),new UpdateSchedules(null,null,second).group());
    }
    @Test void controllerRequiresEtlPermissionBeforeAnyReads() throws Exception {
        CompanyAdminGuard guard=mock(CompanyAdminGuard.class); UpdateStore store=mock(UpdateStore.class);
        when(guard.require(CompanyCapability.ETL_ACCESS)).thenThrow(new IllegalStateException("denied"));
        UpdateController controller=new UpdateController(guard,mock(CompanyAuditRecorder.class),store,mock(UpdateRuntime.class),mock(UpdateSchedules.class),mock(DataSource.class),mock(UpdateConnections.class));
        assertThrows(IllegalStateException.class,controller::state); verifyNoInteractions(store);
    }
}
