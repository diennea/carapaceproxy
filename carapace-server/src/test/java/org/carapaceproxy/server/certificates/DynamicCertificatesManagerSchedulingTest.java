/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.carapaceproxy.server.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Reconfiguring the execution period from an initial value of '0' to > 0. Because of the zero period the manager never
 * starts and when reconfigured with period > 0 it still won't run unless it was started before (#33).
 * Relocated from ManagersExecutionTest so the scheduler injection can stay package-private.
 *
 * @author paolo
 */
class DynamicCertificatesManagerSchedulingTest {

    @Test
    void dynamicCertificatesManagerExecution() throws Exception {
        RuntimeServerConfiguration config = new RuntimeServerConfiguration();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
        DynamicCertificatesManager man = new DynamicCertificatesManager(null, () -> scheduler);
        man.setConfigurationStore(mock(ConfigurationStore.class));

        // With period 0 the manager never starts
        man.setPeriod(0);
        man.start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never called

        // With new period >0 the manager should run whether started before
        config.setDynamicCertificatesManagerPeriod(1);
        man.reloadConfiguration(config);
        assertThat(man.getPeriod()).isOne();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS)); // once

        man.stop();
        config.setDynamicCertificatesManagerPeriod(0);
        man.reloadConfiguration(config);
        assertThat(man.getPeriod()).isZero();
        man.start();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS)); // never
        man.stop();

        // With new period >0 the manager should not run because not started before.
        config.setDynamicCertificatesManagerPeriod(1);
        man.reloadConfiguration(config);
        assertThat(man.getPeriod()).isOne();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS)); // never

        man.start();
        verify(scheduler, times(2)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS)); // once
    }
}
