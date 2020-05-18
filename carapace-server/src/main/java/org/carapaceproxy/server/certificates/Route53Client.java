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

import static org.carapaceproxy.server.certificates.Route53Client.DnsChallengeAction.CHECK;
import static org.carapaceproxy.server.certificates.Route53Client.DnsChallengeAction.DELETE;
import static org.carapaceproxy.server.certificates.Route53Client.DnsChallengeAction.UPSERT;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.WILDCARD_SYMBOL;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53AsyncClient;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsResponse;
import software.amazon.awssdk.services.route53.model.HostedZone;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByNameRequest;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByNameResponse;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Response;
import software.amazon.awssdk.services.route53.model.TestDnsAnswerRequest;
import software.amazon.awssdk.services.route53.model.TestDnsAnswerResponse;

/**
 * Client for AWS Route53.
 *
 * @author paolo.venturi
 */
public class Route53Client {

    public static enum DnsChallengeAction {
        UPSERT,
        DELETE,
        CHECK
    }

    public static interface DnsChallengeRequestCallback<T> {

        void onComplete(T res, Throwable err);

    }

    private static final String DNS_CHALLENGE_PREFIX = "_acme-challenge.";
    private static final String HOSTEDZONE_ID_PREFIX = "/hostedzone/";

    private final Route53AsyncClient client;

    public Route53Client(String awsAccessKey, String awsSecretKey) {

        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(awsAccessKey, awsSecretKey);

        client = Route53AsyncClient.builder()
                .region(Region.AWS_GLOBAL)
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build();
    }

    public void close() {
        client.close();
    }

    public void createDnsChallengeForDomain(String domain, String digest, DnsChallengeRequestCallback<ChangeResourceRecordSetsResponse> callback) {
        performActionOnDnsChallengeForDomain(domain, digest, UPSERT, callback);
    }

    public void deleteDnsChallengeForDomain(String domain, String digest, DnsChallengeRequestCallback<ChangeResourceRecordSetsResponse> callback) {
        performActionOnDnsChallengeForDomain(domain, digest, DELETE, callback);
    }

    public void isDnsChallengeForDomainAvailable(String domain, String digest, DnsChallengeRequestCallback<Boolean> callback) {
        DnsChallengeRequestCallback<TestDnsAnswerResponse> check = (TestDnsAnswerResponse res, Throwable err) -> {
            if (err == null) {
                callback.onComplete(res.recordData().size() == 1 && res.recordData().get(0).equals("\"" + digest + "\""), null);
            } else {
                callback.onComplete(null, err);
            }
        };
        performActionOnDnsChallengeForDomain(domain, null, CHECK, check);
    }

    private void performActionOnDnsChallengeForDomain(String domain, String digest, DnsChallengeAction action, DnsChallengeRequestCallback callback) {
        String dnsName = domain.replace(WILDCARD_SYMBOL, "") + ".";
        String challengeName = DNS_CHALLENGE_PREFIX + dnsName;

        // find out the hostedzone where to update the acme dns-challenge txt record
        CompletableFuture<ListHostedZonesByNameResponse> futureFindHZ = client.listHostedZonesByName(
                ListHostedZonesByNameRequest.builder().dnsName(dnsName).build()
        );

        futureFindHZ.whenComplete((resFindHZ, excFindHZ) -> {
            if (excFindHZ == null && resFindHZ.sdkHttpResponse().isSuccessful()) {
                if (resFindHZ.hostedZones().isEmpty()) {
                    callback.onComplete(null, new NoSuchElementException("No hostedzones found for dns: " + dnsName));
                    return;
                }
                HostedZone hostedzone = resFindHZ.hostedZones().get(0);
                if (!hostedzone.name().equals(dnsName)) {
                    callback.onComplete(null, new NoSuchElementException("Unable to find hostedzone for dns: " + dnsName));
                    return;
                }
                String idhostedzone = hostedzone.id().replace(HOSTEDZONE_ID_PREFIX, "");
                CompletableFuture<? extends Route53Response> future;
                switch (action) {
                    case UPSERT:
                        // ACME DNS-challenge TXT record upsert
                        future = client.changeResourceRecordSets(
                                acmeDnsChallengeUpdateRequest(idhostedzone, challengeName, digest, ChangeAction.UPSERT)
                        );
                        break;
                    case DELETE:
                        // ACME DNS-challenge TXT record delete
                        future = client.changeResourceRecordSets(
                                acmeDnsChallengeUpdateRequest(idhostedzone, challengeName, digest, ChangeAction.DELETE)
                        );
                        break;
                    case CHECK:
                        // ACME DNS-challenge TXT record availability check
                        future = client.testDNSAnswer(TestDnsAnswerRequest.builder()
                                .hostedZoneId(idhostedzone)
                                .recordName(challengeName)
                                .recordType(RRType.TXT)
                                .build()
                        );
                        break;
                    default:
                        callback.onComplete(null, new IllegalArgumentException("Unexpected value for action: " + action));
                        return;
                }
                future.whenComplete((res, exc) -> {
                    if (exc == null && res.sdkHttpResponse().isSuccessful()) {
                        callback.onComplete(res, null);
                    } else {
                        callback.onComplete(null, exc);
                    }
                });
            } else {
                callback.onComplete(null, excFindHZ);
            }
        });
    }

    private static ChangeResourceRecordSetsRequest acmeDnsChallengeUpdateRequest(String idhostedzone, String challengeName, String digest, ChangeAction action) {
        ResourceRecordSet txtRecord = ResourceRecordSet.builder()
                .name(challengeName)
                .ttl(3600L) // an hour
                .type(RRType.TXT)
                .resourceRecords(ResourceRecord.builder().value("\"" + digest + "\"").build())
                .build();

        Change txtRecordCreateRequest = Change.builder()
                .action(action)
                .resourceRecordSet(txtRecord)
                .build();

        ChangeBatch changeBatch = ChangeBatch.builder()
                .changes(txtRecordCreateRequest)
                .build();

        return ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(idhostedzone)
                .changeBatch(changeBatch)
                .build();
    }

}
