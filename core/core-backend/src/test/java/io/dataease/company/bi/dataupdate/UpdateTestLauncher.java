package io.dataease.company.bi.dataupdate;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public class UpdateTestLauncher {
    public static void main(String[] args) {
        var listener=new SummaryGeneratingListener(); var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectPackage("io.dataease.company.bi.dataupdate")).build());
        var writer=new java.io.PrintWriter(System.out); listener.getSummary().printTo(writer); listener.getSummary().printFailuresTo(writer);
        if (listener.getSummary().getTotalFailureCount()>0 || listener.getSummary().getTestsFoundCount()==0) System.exit(1);
    }
}
