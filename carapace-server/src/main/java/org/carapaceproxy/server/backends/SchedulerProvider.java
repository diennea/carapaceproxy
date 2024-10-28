package org.carapaceproxy.server.backends;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

public interface SchedulerProvider extends Supplier<ScheduledExecutorService> {
}
