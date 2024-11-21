package org.carapaceproxy.server.config;

import static org.carapaceproxy.server.config.DirectorConfiguration.ALL_BACKENDS;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafeBackendSelector implements BackendSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafeBackendSelector.class);
    private final SequencedCollection<String> backends;
    private final Map<String, DirectorConfiguration> directors;
    private final BackendHealthManager healthManager;

    public static BackendSelector forMapper(final EndpointMapper mapper) {
        final var directors = mapper.getDirectors()
                .stream()
                .collect(Collectors.toUnmodifiableMap(DirectorConfiguration::getId, Function.identity()));
        return new SafeBackendSelector(mapper.getBackends().sequencedKeySet(), directors, mapper.getBackendHealthManager());
    }

    public SafeBackendSelector(final SequencedCollection<String> allBackendIds, final Map<String, DirectorConfiguration> directors, final BackendHealthManager healthManager) {
        this.backends = allBackendIds;
        this.directors = directors;
        this.healthManager = healthManager;
    }

    @Override
    public List<String> selectBackends(final String userId, final String sessionId, final String director) {
        if (!directors.containsKey(director)) {
            LOGGER.error("Director \"{}\" not configured, while handling request userId={} sessionId={}", director, userId, sessionId);
            return List.of();
        }
        final DirectorConfiguration directorConfig = directors.get(director);
        if (directorConfig.getBackends().contains(ALL_BACKENDS)) {
            return sortByConnections(backends);
        }
        return sortByConnections(directorConfig.getBackends());
    }

    public List<String> sortByConnections(final SequencedCollection<String> backendIds) {
        return backendIds.stream().sorted(Comparator.comparingLong(this::connections)).toList();
    }

    private long connections(final String backendId) {
        final BackendHealthStatus backendStatus = healthManager.getBackendStatus(backendId);
        return switch (backendStatus.getStatus()) {
            case DOWN -> Long.MAX_VALUE; // backends that are down are put last, but not dropped
            case COLD -> healthManager.exceedsCapacity(backendId)
                    ? Long.MAX_VALUE - 1 // cold backends that exceed safe capacity are put last, just before down ones
                    : backendStatus.getConnections();
            case STABLE -> backendStatus.getConnections();
        };
    }
}
