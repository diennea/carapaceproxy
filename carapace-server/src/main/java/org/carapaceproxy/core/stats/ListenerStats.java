package org.carapaceproxy.core.stats;

import org.carapaceproxy.server.config.HostPort;

public interface ListenerStats {
    StatCounter requests(HostPort hostPort);

    StatGauge clients();

    interface StatCounter {
        void increment();

        int get();
    }

    interface StatGauge extends StatCounter {
        @Override
        void increment();

        void decrement();
    }
}
