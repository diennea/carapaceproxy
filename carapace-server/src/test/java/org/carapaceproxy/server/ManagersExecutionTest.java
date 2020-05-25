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
package org.carapaceproxy.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.carapaceproxy.EndpointMapper;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/**
 *
 * @author paolo
 *
 * Test about reconfiguring the execution period from initial value of '0' to > 0. Because of the zero period the
 * manager never start and when reconfigured with period > 0 it still won't run (#33).
 *
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({BackendHealthManager.class, DynamicCertificatesManager.class})
public class ManagersExecutionTest {

    @Test
    public void testBackendHealthManagerExecution() {
        RuntimeServerConfiguration config = new RuntimeServerConfiguration();
        BackendHealthManager man = new BackendHealthManager(config, mock(EndpointMapper.class));

        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        when(timer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
        PowerMockito.mockStatic(Executors.class);
        when(Executors.newSingleThreadScheduledExecutor()).thenReturn(timer);

        // With 0 period the manager never start
        man.setPeriod(0);
        man.start();
        verify(timer, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never called

        // With new period >0 the manager should run whether started before
        config.setHealthProbePeriod(1);
        man.reloadConfiguration(config, mock(EndpointMapper.class));
        assertEquals(1, man.getPeriod());
        verify(timer, times(1)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // once

        man.stop();
        config.setHealthProbePeriod(0);
        man.reloadConfiguration(config, mock(EndpointMapper.class));
        assertEquals(0, man.getPeriod());
        man.start();
        verify(timer, times(1)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never
        man.stop();

        // With new period >0 the manager should not run because not started before.
        config.setHealthProbePeriod(1);
        man.reloadConfiguration(config, mock(EndpointMapper.class));
        assertEquals(1, man.getPeriod());
        verify(timer, times(1)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never

        man.start();
        verify(timer, times(2)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // once
    }

    @Test
    public void testDynamicCertificatesManagerExecution() throws ConfigurationNotValidException {
        RuntimeServerConfiguration config = new RuntimeServerConfiguration();
        DynamicCertificatesManager man = new DynamicCertificatesManager(null);

        man.setConfigurationStore(mock(ConfigurationStore.class));

        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
        PowerMockito.mockStatic(Executors.class);
        when(Executors.newSingleThreadScheduledExecutor(any())).thenReturn(scheduler);

        // With 0 period the manager never start
        man.setPeriod(0);
        man.start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never called

        // With new period >0 the manager should run whether started before
        config.setDynamicCertificatesManagerPeriod(1);
        man.reloadConfiguration(config);
        assertEquals(1, man.getPeriod());
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // once

        man.stop();
        config.setDynamicCertificatesManagerPeriod(0);
        man.reloadConfiguration(config);
        assertEquals(0, man.getPeriod());
        man.start();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never
        man.stop();

        // With new period >0 the manager should not run because not started before.
        config.setDynamicCertificatesManagerPeriod(1);
        man.reloadConfiguration(config);
        assertEquals(1, man.getPeriod());
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // never

        man.start();
        verify(scheduler, times(2)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)); // once
    }

}
