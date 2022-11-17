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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.utils.CertificatesUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53AsyncClient;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
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

    private static final Logger LOG = Logger.getLogger(Route53Client.class.getName());

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

    public boolean createDnsChallengeForDomain(String domain, String digest) {
        return performActionOnDnsChallengeForDomain(domain, digest, UPSERT);
    }

    public boolean deleteDnsChallengeForDomain(String domain, String digest) {
        return performActionOnDnsChallengeForDomain(domain, digest, DELETE);
    }

    public boolean isDnsChallengeForDomainAvailable(String domain, String digest) {
        return performActionOnDnsChallengeForDomain(domain, digest, CHECK);
    }

    private boolean performActionOnDnsChallengeForDomain(String domain, String digest, DnsChallengeAction action) {
        String dnsName = CertificatesUtils.removeWildcard(domain) + ".";
        String challengeName = DNS_CHALLENGE_PREFIX + dnsName;

        try {
            // find out the hostedzone where to update the acme dns-challenge txt record
            ListHostedZonesByNameResponse res = client.listHostedZonesByName(
                    ListHostedZonesByNameRequest.builder().dnsName(dnsName).build()
            ).exceptionally(ext -> {
                LOG.log(Level.SEVERE, "ERROR performing {0} action for dns name {1}. Reason: {2}", new Object[]{action, dnsName, ext});
                return null;
            }).get();

            if (res == null || !res.sdkHttpResponse().isSuccessful()) {
                return false;
            }
            if (res.hostedZones().isEmpty()) {
                LOG.log(Level.SEVERE, "No hostedzones found for dns name {0}", dnsName);
                return false;
            }
            HostedZone hostedzone = res.hostedZones().get(0);
            if (!hostedzone.name().equals(dnsName)) {
                LOG.log(Level.SEVERE, "Unable to find hostedzone for dns name {0}", dnsName);
                return false;
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
                    throw new IllegalArgumentException("Unexpected value for action: " + action);
            }
            Route53Response actionResult;
            actionResult = future.exceptionally(ext -> {
                LOG.log(Level.SEVERE, "ERROR performing {0} action for dns name {1}. Reason: {2}", new Object[]{action, dnsName, ext});
                return null;
            }).get();
            if (actionResult != null && actionResult.sdkHttpResponse().isSuccessful()) {
                if (action == CHECK) {
                    TestDnsAnswerResponse check = (TestDnsAnswerResponse) actionResult;
                    return check.recordData().size() == 1 && check.recordData().get(0).equals("\"" + digest + "\"");
                }
                return true;
            }
        } catch (InterruptedException | ExecutionException ex) {
            LOG.log(Level.SEVERE, "ERROR performing {0} action for dns name {1}. Reason: {2}", new Object[]{action, dnsName, ex});
        }

        return false;
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
