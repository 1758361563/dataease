package io.dataease.company.bi.etl;

import org.quartz.*;
import io.dataease.utils.CommonBeanFactory;
import io.dataease.utils.LogUtil;

@DisallowConcurrentExecution
public class EtlQuartzJob implements Job {
    @Override public void execute(JobExecutionContext context) {
        try {
            var registrar=(EtlQuartzRegistrar)CommonBeanFactory.getBean(EtlQuartzRegistrar.class);
            if (!registrar.ready()) return;
            var switches=(EtlSwitchService)CommonBeanFactory.getBean(EtlSwitchService.class);
            if (!switches.effective()) { LogUtil.info("ETL: 本实例开关未生效，跳过触发");return; }
            var runner=(EtlChainRunner)CommonBeanFactory.getBean(EtlChainRunner.class);
            runner.submit("refresh",null,false,context.getScheduledFireTime().toInstant(),true);
        } catch (Exception e) { LogUtil.warn("ETL: 触发未执行，请检查开关、任务状态和数据源配置"); }
    }
}
