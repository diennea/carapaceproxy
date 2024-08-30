package org.carapaceproxy.core.stats;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.carapaceproxy.server.config.HostPort;

public class PrometheusListenerStats implements ListenerStats {
    public static final ListenerStats INSTANCE = new PrometheusListenerStats();
    private static final Gauge CURRENT_CONNECTED_CLIENTS_GAUGE = PrometheusUtils.createGauge(
            "clients", "current_connected", "currently connected clients"
    ).register();
    private static final Counter TOTAL_REQUESTS_PER_LISTENER_COUNTER = PrometheusUtils.createCounter(
            "listeners", "requests_total", "total requests", "listener"
    ).register();

    private PrometheusListenerStats() {
    }

    @Override
    public StatCounter requests(final HostPort hostPort) {
        return new StatCounter() {
            private final Counter.Child counter = TOTAL_REQUESTS_PER_LISTENER_COUNTER
                    .labels(hostPort.host() + "_" + hostPort.port());

            @Override
            public void increment() {
                counter.inc();
            }

            @Override
            public int get() {
                return (int) counter.get();
            }
        };
    }

    @Override
    public StatGauge clients() {
        return new StatGauge() {
            @Override
            public void increment() {
                CURRENT_CONNECTED_CLIENTS_GAUGE.inc();
            }

            @Override
            public int get() {
                return (int) CURRENT_CONNECTED_CLIENTS_GAUGE.get();
            }

            @Override
            public void decrement() {
                CURRENT_CONNECTED_CLIENTS_GAUGE.dec();
            }
        };
    }
}
