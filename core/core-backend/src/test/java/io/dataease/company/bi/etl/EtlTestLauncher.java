package io.dataease.company.bi.etl;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/** Allows the cached local JDK/dependency workflow as well as Maven Surefire. */
public class EtlTestLauncher {
    public static void main(String[] args) {
        var listener=new SummaryGeneratingListener();
        var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectPackage("io.dataease.company.bi.etl")).build());
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTotalFailureCount()>0) {
            listener.getSummary().getFailures().forEach(f -> f.getException().printStackTrace());
            System.exit(1);
        }
    }
}
