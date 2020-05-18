/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.server.certificates;

/**
 * Class for testing real AWS Route53 calls.
 *
 * @author paolo.venturi
 */
public class Route53ClientTest {

    //@Test
    public void testCRUD() throws InterruptedException {
        Route53Client r53Client = new Route53Client(
                null, null
        );

        r53Client.createDnsChallengeForDomain(
                "*.testcara.tld",
                "digest",
                (res, err) -> {
                    if (err == null) {
                        System.out.println("RES: " + res);
                    } else {
                        System.out.println("ERR: " + err);
                    }
                }
        );
        Thread.sleep(1_000 * 5);
        r53Client.isDnsChallengeForDomainAvailable(
                "*.testcara.tld",
                "digest",
                (res, err) -> {
                    if (err == null) {
                        System.out.println("RES: " + res);
                    } else {
                        System.out.println("ERR: " + err);
                    }
                }
        );

        r53Client.deleteDnsChallengeForDomain(
                "*.testcara.tld",
                "digest",
                (res, err) -> {
                    if (err == null) {
                        System.out.println("RES: " + res);
                    } else {
                        System.out.println("ERR: " + err);
                    }
                }
        );

        Thread.sleep(1_000 * 5);
        r53Client.isDnsChallengeForDomainAvailable(
                "*.testcara.tld",
                "digest",
                (res, err) -> {
                    if (err == null) {
                        System.out.println("RES: " + res);
                    } else {
                        System.out.println("ERR: " + err);
                    }
                }
        );
        Thread.sleep(1_000 * 60);
    }
}
