package io.dataease.dataset.manage;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class PreviewTestLauncher {
    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass(DatasetPreviewRequestTest.class), selectClass(PreviewExecutionTest.class)).build());
        var writer = new java.io.PrintWriter(System.out);
        listener.getSummary().printTo(writer);
        listener.getSummary().printFailuresTo(writer);
        if (listener.getSummary().getTotalFailureCount() > 0 || listener.getSummary().getTestsFoundCount() == 0) System.exit(1);
    }
}
