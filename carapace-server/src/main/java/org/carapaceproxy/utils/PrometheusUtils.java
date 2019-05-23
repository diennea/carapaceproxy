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
package org.carapaceproxy.utils;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.SimpleCollector;
import io.prometheus.client.Summary;

/**
 *
 * @author dennis.mercuriali
 */
public class PrometheusUtils {

    /**
     * Creates a new Counter. The created Counter will not be registered to metrics registry
     *
     * @param namespace
     * @param name
     * @param help
     * @param labels
     * @return
     */
    public static Counter createCounter(String namespace, String name, String help, String... labels) {
        Counter.Builder builder = Counter.build()
                .namespace(namespace)
                .name(name)
                .help(help);
        
        if (labels != null && labels.length > 0) {
            builder.labelNames(labels);
        }

        return builder.create();
    }

    /**
     * Creates a new Gauge. The created Gauge will not be registered to metrics registry
     *
     * @param namespace
     * @param name
     * @param help
     * @param labels
     * @return
     */
    public static Gauge createGauge(String namespace, String name, String help, String... labels) {
        Gauge.Builder builder =  Gauge.build()
                .namespace(namespace)
                .name(name)
                .help(help);
        
        if (labels != null && labels.length > 0) {
            builder.labelNames(labels);
        }

        return builder.create();
    }

    /**
     * Creates a new Summary. The created Summary will not be registered to metrics registry<br>
     * Summary is created with 0.5, 0.9 and 0.99 quantiles
     *
     * @param namespace
     * @param name
     * @param help
     * @param labels
     * @return
     */
    public static Summary createSummary(String namespace, String name, String help, String... labels) {
        Summary.Builder builder = Summary.build()
                .namespace(namespace)
                .name(name)
                .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                .quantile(0.9, 0.01) // Add 90th percentile with 1% tolerated error
                .quantile(0.99, 0.001) // Add 99th percentile with 0.1% tolerated error
                .help(help);

        if (labels != null && labels.length > 0) {
            builder.labelNames(labels);
        }

        return builder.create();
    }
}
