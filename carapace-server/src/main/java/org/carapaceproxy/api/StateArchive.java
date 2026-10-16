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
package org.carapaceproxy.api;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.configstore.StoreSnapshot.CertificateRow;
import org.carapaceproxy.configstore.StoreSnapshot.EncodedKeyPair;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.CarapaceVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The backup archive of the whole Carapace state, version 1 of the format.
 * <br>
 * It is storage agnostic: the values are those of a {@link StoreSnapshot}. The format is the same on every
 * Carapace line, so that an archive taken on an older version always restores on a newer one.
 *
 * @param format          always {@value #FORMAT}
 * @param formatVersion   always {@value #FORMAT_VERSION}
 * @param carapaceVersion the version of the Carapace that took the backup, {@code null} when unknown
 * @param createdAt       when the backup was taken, ISO-8601 UTC; informative only
 * @param config          the dynamic configuration
 * @param keyPairs        the key pair of every domain
 * @param acmeAccountKeys the ACME account key pairs, by provider
 * @param certificates    the dynamic certificates, by domain
 */
@JsonPropertyOrder({"format", "formatVersion", "carapaceVersion", "createdAt", "config", "keyPairs", "acmeAccountKeys", "certificates"})
public record StateArchive(
        String format,
        int formatVersion,
        String carapaceVersion,
        String createdAt,
        Map<String, String> config,
        Map<String, EncodedKeyPair> keyPairs,
        Map<String, EncodedKeyPair> acmeAccountKeys,
        Map<String, CertificateRow> certificates) {

    /**
     * The value of {@link #format()}.
     */
    public static final String FORMAT = "carapace-state";

    /**
     * The only value of {@link #formatVersion()} this version reads and writes.
     */
    public static final int FORMAT_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(StateArchive.class);

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?!\\d)");

    /**
     * The largest archive {@link #read(InputStream)} accepts, about three times the state of 2000 certificates.
     */
    public static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;

    // duplicated keys are refused, as they would hide a value, and so is anything after the archive;
    // unknown fields are what a newer line adds
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Wraps a snapshot of the running store into an archive, stamped with the running version and the current time.
     *
     * @param snapshot the exported state
     * @return the archive
     */
    public static StateArchive of(final StoreSnapshot snapshot) {
        return new StateArchive(
                FORMAT,
                FORMAT_VERSION,
                CarapaceVersion.VERSION,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                snapshot.config(),
                snapshot.keyPairs(),
                snapshot.acmeAccountKeys(),
                snapshot.certificates()
        );
    }

    /**
     * Reads an archive, checking the format and its version.
     *
     * @param in the JSON archive
     * @return the archive
     * @throws ConfigurationNotValidException if the JSON cannot be read, is malformed, has duplicated keys or trailing
     *                                        content, is larger than {@value #MAX_ARCHIVE_BYTES} bytes, is not of format
     *                                        version {@value #FORMAT_VERSION} or lacks one of its four sections
     */
    public static StateArchive read(final InputStream in) throws ConfigurationNotValidException {
        final StateArchive archive;
        try {
            final byte[] bytes = in.readNBytes(MAX_ARCHIVE_BYTES + 1);
            if (bytes.length > MAX_ARCHIVE_BYTES) {
                throw new ConfigurationNotValidException("The archive is larger than " + (MAX_ARCHIVE_BYTES >> 20) + " MB");
            }
            archive = MAPPER.readValue(new ByteArrayInputStream(bytes), StateArchive.class);
        } catch (JsonProcessingException err) {
            // Jackson quotes the offending token, which may be key material: say only where it is
            final JsonLocation at = err.getLocation();
            throw new ConfigurationNotValidException("Invalid archive: malformed JSON"
                    + (at == null ? "" : " at line " + at.getLineNr() + ", column " + at.getColumnNr()));
        } catch (IOException err) {
            throw new ConfigurationNotValidException("Cannot read the archive: " + err.getMessage());
        }
        if (archive == null || !FORMAT.equals(archive.format())) {
            throw new ConfigurationNotValidException("Invalid archive: format is not \"" + FORMAT + "\"");
        }
        if (archive.formatVersion() != FORMAT_VERSION) {
            // an unknown format cannot even be read safely, so there is no way around this
            throw new ConfigurationNotValidException("Unsupported archive formatVersion " + archive.formatVersion()
                    + ", this Carapace reads only " + FORMAT_VERSION);
        }
        // every archive of this format has them, even empty: a missing one would wipe that part of the state
        if (archive.config() == null || archive.keyPairs() == null || archive.acmeAccountKeys() == null || archive.certificates() == null) {
            throw new ConfigurationNotValidException("Invalid archive: config, keyPairs, acmeAccountKeys and certificates are all required");
        }
        return archive;
    }

    /**
     * Writes the archive as JSON.
     *
     * @return the JSON bytes, fields in the documented order
     * @throws IOException if the archive cannot be serialized
     */
    public byte[] toJson() throws IOException {
        return MAPPER.writeValueAsBytes(this);
    }

    /**
     * Turns the archive into the snapshot to restore on this Carapace.
     * <br>
     * An archive of the same or an older version is always accepted; one of a newer version only on request.
     * Only {@code major.minor.patch} is compared, the qualifier is ignored: {@code 2.1.4-SNAPSHOT} equals {@code 2.1.4}.
     * An archive without a valid version is refused; the check is skipped with a warning when either side is a dev build.
     *
     * @param runtimeVersion     the version of the running Carapace
     * @param acceptNewerVersion whether to accept an archive taken on a newer version, at the caller's own risk
     * @return the snapshot to restore, not yet validated
     * @throws ConfigurationNotValidException if the archive has no valid version, or comes from a newer version and that
     *                                        is not accepted
     */
    public StoreSnapshot toSnapshot(final String runtimeVersion, final boolean acceptNewerVersion) throws ConfigurationNotValidException {
        final int[] archived = parseVersion(carapaceVersion);
        final int[] running = parseVersion(runtimeVersion);
        if ("dev".equals(carapaceVersion) || running == null) {
            LOG.warn("Cannot compare the archive version {} with the running version {}: version check skipped",
                    carapaceVersion, runtimeVersion);
        } else if (archived == null) {
            throw new ConfigurationNotValidException("The archive has no valid carapaceVersion: " + carapaceVersion);
        } else if (Arrays.compare(archived, running) > 0) {
            if (!acceptNewerVersion) {
                throw new ConfigurationNotValidException("The archive comes from Carapace " + carapaceVersion
                        + ", newer than the running " + runtimeVersion + "; restore it with acceptNewerVersion=true at your own risk");
            }
            LOG.warn("Restoring an archive of Carapace {} on the older {}, as requested: what this version does not know is ignored",
                    carapaceVersion, runtimeVersion);
        }
        return new StoreSnapshot(config, keyPairs, acmeAccountKeys, certificates);
    }

    private static int[] parseVersion(final String version) {
        if (version == null) {
            return null;
        }
        final Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
    }
}
