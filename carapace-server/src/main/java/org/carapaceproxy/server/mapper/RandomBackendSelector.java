package org.carapaceproxy.server.mapper;

import static org.carapaceproxy.server.config.DirectorConfiguration.ALL_BACKENDS;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.random.RandomGenerator;
import org.carapaceproxy.server.config.BackendSelector;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The selector chooses an available backend randomly.
 * This means that backend preference is determined by shuffling the resulting list.
 *
 * @see Collections#shuffle(List, RandomGenerator)
 * @see SecureRandom
 */
public class RandomBackendSelector implements BackendSelector {
    private static final Logger LOG = LoggerFactory.getLogger(RandomBackendSelector.class);
    private static final RandomGenerator RANDOM = new SecureRandom();

    private final SequencedCollection<String> allBackendIds;
    private final Map<String, DirectorConfiguration> directors;

    private RandomBackendSelector(final SequencedCollection<String> allBackendIds, final Map<String, DirectorConfiguration> directors) {
        this.allBackendIds = allBackendIds;
        this.directors = directors;
    }

    @Override
    public List<String> selectBackends(final String userId, final String sessionId, final String director) {
        if (!directors.containsKey(director)) {
            LOG.error("Director \"{}\" not configured, while handling request userId={} sessionId={}", director, userId, sessionId);
            return List.of();
        }
        final DirectorConfiguration directorConfig = directors.get(director);
        if (directorConfig.getBackends().contains(ALL_BACKENDS)) {
            return shuffleCopy(allBackendIds);
        }
        return shuffleCopy(directorConfig.getBackends());
    }

    public List<String> shuffleCopy(final SequencedCollection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() == 1) {
            return List.copyOf(ids);
        }
        final List<String> result = new ArrayList<>(ids);
        Collections.shuffle(result, RANDOM);
        return List.copyOf(result);
    }

}
