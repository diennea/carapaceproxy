package org.carapaceproxy.server.config;

import static org.carapaceproxy.server.config.DirectorConfiguration.ALL_BACKENDS;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafeBackendSelector implements BackendSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafeBackendSelector.class);
    private final EndpointMapper mapper;

    public SafeBackendSelector(final EndpointMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<String> selectBackends(final String userId, final String sessionId, final String director) {
        final Map<String, DirectorConfiguration> directors = mapper.getDirectors();
        if (!directors.containsKey(director)) {
            LOGGER.error("Director \"{}\" not configured, while handling request userId={} sessionId={}", director, userId, sessionId);
            return List.of();
        }
        final DirectorConfiguration directorConfig = directors.get(director);
        if (directorConfig.getBackends().contains(ALL_BACKENDS)) {
            return sortByConnections(mapper.getBackends().sequencedKeySet());
        }
        return sortByConnections(directorConfig.getBackends());
    }

    public List<String> sortByConnections(final SequencedCollection<String> backendIds) {
        return backendIds.stream().sorted(Comparator.comparingLong(this::connections)).toList();
    }

    private long connections(final String backendId) {
        final BackendHealthStatus backendStatus = mapper.getBackendHealthManager().getBackendStatus(backendId);
        return switch (backendStatus.getStatus()) {
            case DOWN -> Long.MAX_VALUE; // backends that are down are put last, but not dropped
            case COLD -> mapper.getBackendHealthManager().exceedsCapacity(backendId)
                    ? Long.MAX_VALUE - 1 // cold backends that exceed safe capacity are put last, just before down ones
                    : backendStatus.getConnections();
            case STABLE -> backendStatus.getConnections();
        };
    }
}
