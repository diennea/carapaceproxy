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
package org.carapaceproxy.server.mapper;

import static org.carapaceproxy.core.StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
import static org.carapaceproxy.core.StaticContentsManager.DEFAULT_MAINTENANCE_MODE_ERROR;
import static org.carapaceproxy.core.StaticContentsManager.DEFAULT_NOT_FOUND;
import static org.carapaceproxy.core.StaticContentsManager.IN_MEMORY_RESOURCE;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.BackendSelector;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.config.SafeBackendSelector;
import org.carapaceproxy.server.filters.UrlEncodedQueryString;
import org.carapaceproxy.server.mapper.CustomHeader.HeaderMode;
import org.carapaceproxy.server.mapper.MapResult.Action;
import org.carapaceproxy.server.mapper.requestmatcher.RegexpRequestMatcher;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;
import org.carapaceproxy.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference implementation of an {@link EndpointMapper}.
 */
public class StandardEndpointMapper extends EndpointMapper {

    private static final Logger LOG = LoggerFactory.getLogger(StandardEndpointMapper.class);
    private static final String DEFAULT_NOT_FOUND_ACTION = "not-found";
    private static final String DEFAULT_INTERNAL_ERROR_ACTION = "internal-error";
    private static final String DEFAULT_MAINTENANCE_ACTION = "maintenance";
    private static final String DEFAULT_BAD_REQUEST_ACTION = "bad-request";
    private static final String DEFAULT_SERVICE_UNAVAILABLE_ACTION = "service-unavailable";
    private static final String ACME_CHALLENGE_URI_PATTERN = "/\\.well-known/acme-challenge/";
    public static final String ACME_CHALLENGE_ROUTE_ACTION_ID = "acme-challenge";
    public static final String DEBUGGING_HEADER_DEFAULT_NAME = "X-Proxy-Path";
    public static final String DEBUGGING_HEADER_ID = "mapper-debug";
    private static final int DEFAULT_CAPACITY = 10; // number of connections

    // The map is wiped out whenever a new configuration is applied
    private final SequencedMap<String, BackendConfiguration> backends = new LinkedHashMap<>();
    private final SequencedMap<String, DirectorConfiguration> directors = new LinkedHashMap<>();
    private final List<RouteConfiguration> routes = new ArrayList<>();
    private final Map<String, ActionConfiguration> actions = new HashMap<>();
    public final Map<String, CustomHeader> headers = new HashMap<>();
    private final BackendSelector backendSelector;

    private String defaultNotFoundAction = DEFAULT_NOT_FOUND_ACTION;
    private String defaultInternalErrorAction = DEFAULT_INTERNAL_ERROR_ACTION;
    private String defaultMaintenanceAction = DEFAULT_MAINTENANCE_ACTION;
    private String defaultBadRequestAction = DEFAULT_BAD_REQUEST_ACTION;
    private String defaultServiceUnavailable = DEFAULT_SERVICE_UNAVAILABLE_ACTION;

    private String forceDirectorParameter = "x-director";
    private String forceBackendParameter = "x-backend";

    private String debuggingHeaderName = DEBUGGING_HEADER_DEFAULT_NAME;
    private boolean debuggingHeaderEnabled = false;

    public StandardEndpointMapper(final HttpProxyServer parent) {
        this(parent, SafeBackendSelector::new);
    }

    public StandardEndpointMapper(final HttpProxyServer parent, final BackendSelector.SelectorFactory backendSelector) {
        super(parent);
        this.backendSelector = backendSelector.build(this);
    }

    @Override
    public MapResult map(final ProxyRequest request) {
        // If the HOST header is null (when on HTTP/1.1 or less), then return bad request
        // https://www.rfc-editor.org/rfc/rfc2616#page-38
        if (StringUtils.isBlank(request.getRequestHostname())) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Request {} header host is null or empty", request.getUri());
            }
            return MapResult.badRequest();
        }
        // Invalid header host
        if (!request.isValidHostAndPort(request.getRequestHostname())) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Invalid header host {} for request {}", request.getRequestHostname(), request.getUri());
            }
            return MapResult.badRequest();
        }
        for (final RouteConfiguration route : routes) {
            if (!route.isEnabled()) {
                continue;
            }
            final boolean matchResult = route.matches(request);
            if (LOG.isTraceEnabled()) {
                LOG.trace("route {}, map {} -> {}", route.getId(), request.getUri(), matchResult);
            }
            if (!matchResult) {
                continue;
            }

            if (getCurrentConfiguration().isMaintenanceModeEnabled()) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Maintenance mode is enable: request uri: {}", request.getUri());
                }
                return MapResult.maintenanceMode(route.getId());
            }

            final ActionConfiguration action = actions.get(route.getAction());
            if (action == null) {
                LOG.info("no action \"{}\" -> not-found for {}, valid {}", route.getAction(), request.getUri(), actions.keySet());
                return MapResult.internalError(route.getId());
            }

            switch (action.getType()) {
                case ActionConfiguration.TYPE_REDIRECT -> {
                    return MapResult.builder()
                            .host(action.getRedirectHost())
                            .port(action.getRedirectPort())
                            .action(Action.REDIRECT)
                            .routeId(route.getId())
                            .redirectLocation(action.getRedirectLocation())
                            .redirectProto(action.getRedirectProto())
                            .redirectPath(action.getRedirectPath())
                            .errorCode(action.getErrorCode())
                            .customHeaders(action.getCustomHeaders())
                            .build();
                }
                case ActionConfiguration.TYPE_STATIC -> {
                    return MapResult.builder()
                            .action(Action.STATIC)
                            .routeId(route.getId())
                            .resource(action.getFile())
                            .errorCode(action.getErrorCode())
                            .customHeaders(action.getCustomHeaders())
                            .build();
                }
                case ActionConfiguration.TYPE_ACME_CHALLENGE -> {
                    String tokenName = request.getUri().replaceFirst(".*" + ACME_CHALLENGE_URI_PATTERN, "");
                    String tokenData = getDynamicCertificatesManager().getChallengeToken(tokenName);
                    if (tokenData == null) {
                        return MapResult.notFound(route.getId());
                    }
                    return MapResult.builder()
                            .action(Action.ACME_CHALLENGE)
                            .routeId(route.getId())
                            .resource(IN_MEMORY_RESOURCE + tokenData)
                            .errorCode(action.getErrorCode())
                            .build();
                }
            }
            final UrlEncodedQueryString queryString = request.getQueryString();

            final List<String> selectedBackends;
            if (queryString.contains(forceBackendParameter)) {
                final String forceBackendParameterValue = queryString.get(forceBackendParameter);
                LOG.info("forcing backend = {} for {}", forceBackendParameterValue, request.getUri());
                selectedBackends = List.of(forceBackendParameterValue);
                LOG.trace("selected {} backends for {}", selectedBackends, request.getUri());
            } else {
                final String director;
                if (queryString.contains(forceDirectorParameter)) {
                    director = queryString.get(forceDirectorParameter);
                    LOG.info("forcing director = {} for {}", director, request.getUri());
                } else {
                    director = action.getDirector();
                }
                selectedBackends = backendSelector.selectBackends(request.getUserId(), request.getSessionId(), director);
                LOG.trace("selected {} backends for {}, director is {}", selectedBackends, request.getUri(), director);
            }

            final Action selectedAction;
            switch (action.getType()) {
                case ActionConfiguration.TYPE_PROXY -> selectedAction = Action.PROXY;
                case ActionConfiguration.TYPE_CACHE -> selectedAction = Action.CACHE;
                default -> {
                    return MapResult.internalError(route.getId());
                }
            }

            for (final String backendId : selectedBackends) {
                final BackendConfiguration backend = this.backends.get(backendId);
                if (backend != null) {
                    final BackendHealthManager backendHealthManager = getBackendHealthManager();
                    final BackendHealthStatus backendStatus = backendHealthManager.getBackendStatus(backend.hostPort());
                    switch (backendStatus.getStatus()) {
                        case DOWN:
                            LOG.info("Backend {} is down, skipping...", backendId);
                            continue;
                        case COLD:
                            if (backendHealthManager.exceedsCapacity(backendId)) {
                                final int capacity = backend.safeCapacity();
                                if (!backendHealthManager.isTolerant()) {
                                    // default behavior, exceeding safe capacity is not tolerated...
                                    LOG.info("Backend {} is cold and exceeds safe capacity of {} connections, skipping...", backendId, capacity);
                                    continue;
                                }
                                /*
                                 * backends are returned by the mapper sorted
                                 * from the most desirable to the less desirable;
                                 * if the execution reaches this point,
                                 * we may use the cold backend even if over the recommended capacity anyway...
                                 */
                                LOG.warn("Cold backend {} exceeds safe capacity of {} connections, but will use it anyway", backendId, capacity);
                            }
                            // falls through
                        case STABLE: {
                            List<CustomHeader> customHeaders = action.getCustomHeaders();
                            if (this.debuggingHeaderEnabled) {
                                customHeaders = new ArrayList<>(customHeaders);
                                final String routingPath = route.getId() + ";"
                                        + action.getId() + ";"
                                        + action.getDirector() + ";"
                                        + backendId;
                                customHeaders.add(new CustomHeader(DEBUGGING_HEADER_ID, debuggingHeaderName, routingPath, HeaderMode.ADD));
                            }
                            return MapResult.builder()
                                    .host(backend.host())
                                    .port(backend.port())
                                    .action(selectedAction)
                                    .routeId(route.getId())
                                    .customHeaders(customHeaders)
                                    .healthStatus(backendStatus)
                                    .ssl(backend.ssl())
                                    .build();
                        }
                    }
                }
            }
            // none of selected backends available
            // return service unavailable if all backend is unavailable
            if (!selectedBackends.isEmpty()) {
                return MapResult.serviceUnavailable(route.getId());
            }
        }
        // no one route matched
        return MapResult.notFound(MapResult.NO_ROUTE);
    }

    private ActionConfiguration getErrorActionConfiguration(final String routeId, final String defaultAction) {
        // Attempt to find a route-specific configuration first
        return routes.stream()
                .filter(r -> r.getId().equalsIgnoreCase(routeId))
                .findFirst()
                .map(RouteConfiguration::getErrorAction)
                .filter(actions::containsKey)
                // If no route-specific configuration or action is found, fallback to the global default
                .or(() -> Optional.of(defaultAction))
                .map(actions::get)
                // Return null if no appropriate action configuration is found
                .orElse(null);
    }

    @Override
    public SimpleHTTPResponse mapInternalError(final String routeid) {
        ActionConfiguration errorAction = getErrorActionConfiguration(routeid, defaultInternalErrorAction);
        if (errorAction != null) {
            return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
        }
        // fallback
        return super.mapInternalError(routeid);
    }

    @Override
    public SimpleHTTPResponse mapServiceUnavailableError(final String routeId) {
        ActionConfiguration errorAction = getErrorActionConfiguration(routeId, defaultServiceUnavailable);
        if (errorAction != null) {
            return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
        }
        // fallback
        return super.mapServiceUnavailableError(routeId);
    }

    @Override
    public SimpleHTTPResponse mapMaintenanceMode(final String routeId) {
        ActionConfiguration maintenanceAction = null;
        // custom for route
        Optional<RouteConfiguration> config = routes.stream().filter(r -> r.getId().equalsIgnoreCase(routeId)).findFirst();
        if (config.isPresent()) {
            String action = config.get().getMaintenanceModeAction();
            if (action != null) {
                maintenanceAction = actions.get(action);
            }
        }
        // custom global
        if (maintenanceAction == null) {
            maintenanceAction = actions.get(defaultMaintenanceAction);
        }
        if (maintenanceAction != null) {
            return new SimpleHTTPResponse(maintenanceAction.getErrorCode(), maintenanceAction.getFile(), maintenanceAction.getCustomHeaders());
        }
        // fallback
        return super.mapMaintenanceMode(routeId);
    }

    @Override
    public SimpleHTTPResponse mapBadRequest() {
        // custom global
        ActionConfiguration errorAction = actions.get(defaultBadRequestAction);
        if (errorAction != null) {
            return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
        }
        // fallback
        return super.mapBadRequest();
    }

    @Override
    public SimpleHTTPResponse mapPageNotFound(final String routeId) {
        // custom global
        ActionConfiguration errorAction = actions.get(defaultNotFoundAction);
        if (errorAction != null) {
            return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
        }
        // fallback
        return super.mapPageNotFound(routeId);
    }

    @Override
    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
        addAction(new ActionConfiguration("proxy-all", ActionConfiguration.TYPE_PROXY, DirectorConfiguration.DEFAULT, null, -1));
        addAction(new ActionConfiguration("cache-if-possible", ActionConfiguration.TYPE_CACHE, DirectorConfiguration.DEFAULT, null, -1));
        addAction(new ActionConfiguration(DEFAULT_NOT_FOUND_ACTION, ActionConfiguration.TYPE_STATIC, null, DEFAULT_NOT_FOUND, 404));
        addAction(new ActionConfiguration(DEFAULT_INTERNAL_ERROR_ACTION, ActionConfiguration.TYPE_STATIC, null, DEFAULT_INTERNAL_SERVER_ERROR, 500));
        addAction(new ActionConfiguration(DEFAULT_MAINTENANCE_ACTION, ActionConfiguration.TYPE_STATIC, null, DEFAULT_MAINTENANCE_MODE_ERROR, 500));

        // Route+Action configuration for Let's Encrypt ACME challenging
        addAction(new ActionConfiguration(
                ACME_CHALLENGE_ROUTE_ACTION_ID, ActionConfiguration.TYPE_ACME_CHALLENGE,
                null, null, HttpResponseStatus.OK.code()
        ));
        addRoute(new RouteConfiguration(
                ACME_CHALLENGE_ROUTE_ACTION_ID, ACME_CHALLENGE_ROUTE_ACTION_ID, true,
                new RegexpRequestMatcher(ProxyRequest.PROPERTY_URI, ".*" + ACME_CHALLENGE_URI_PATTERN + ".*")
        ));

        this.defaultNotFoundAction = properties.getString("default.action.notfound", DEFAULT_NOT_FOUND_ACTION);
        LOG.info("configured default.action.notfound={}", defaultNotFoundAction);

        this.defaultInternalErrorAction = properties.getString("default.action.internalerror", DEFAULT_INTERNAL_ERROR_ACTION);
        LOG.info("configured default.action.internalerror={}", defaultInternalErrorAction);

        this.defaultMaintenanceAction = properties.getString("default.action.maintenance", DEFAULT_MAINTENANCE_ACTION);
        LOG.info("configured default.action.maintenance={}", defaultMaintenanceAction);

        this.defaultBadRequestAction = properties.getString("default.action.badrequest", DEFAULT_BAD_REQUEST_ACTION);
        LOG.info("configured default.action.badrequest={}", defaultBadRequestAction);

        this.defaultServiceUnavailable = properties.getString("default.action.serviceunavailable", DEFAULT_SERVICE_UNAVAILABLE_ACTION);
        LOG.info("configured default.action.serviceunavailable={}", defaultServiceUnavailable);

        this.forceDirectorParameter = properties.getString("mapper.forcedirector.parameter", forceDirectorParameter);
        LOG.info("configured mapper.forcedirector.parameter={}", forceDirectorParameter);

        this.forceBackendParameter = properties.getString("mapper.forcebackend.parameter", forceBackendParameter);
        LOG.info("configured mapper.forcebackend.parameter={}", forceBackendParameter);

        // To add custom debugging header for request chosen mapping-path
        this.debuggingHeaderEnabled = properties.getBoolean("mapper.debug", false);
        LOG.info("configured mapper.debug={}", debuggingHeaderEnabled);

        this.debuggingHeaderName = properties.getString("mapper.debug.name", DEBUGGING_HEADER_DEFAULT_NAME);
        LOG.info("configured mapper.debug.name={}", debuggingHeaderName);

        /*
         * HEADERS
         */
        int max = properties.findMaxIndexForPrefix("header");
        for (int i = 0; i <= max; i++) {
            String prefix = "header." + i + ".";
            String id = properties.getString(prefix + "id", "");
            String name = properties.getString(prefix + "name", "");
            if (!id.isEmpty() && !name.isEmpty()) {
                String value = properties.getString(prefix + "value", "");
                String mode = properties.getString(prefix + "mode", "add").toLowerCase().trim();
                addHeader(id, name, value, mode);
                LOG.info("configured header {} name:{}, value:{}", id, name, value);
            }
        }

        /*
         * ACTIONS
         */
        max = properties.findMaxIndexForPrefix("action");
        for (int i = 0; i <= max; i++) {
            String prefix = "action." + i + ".";
            String id = properties.getString(prefix + "id", "");
            boolean enabled = properties.getBoolean(prefix + "enabled", false);
            if (!id.isEmpty() && enabled) {
                String actionType = properties.getString(prefix + "type", ActionConfiguration.TYPE_PROXY);
                String file = properties.getString(prefix + "file", "");
                String director = properties.getString(prefix + "director", DirectorConfiguration.DEFAULT);
                int code = properties.getInt(prefix + "code", -1);
                // Headers
                List<CustomHeader> customHeaders = new ArrayList<>();
                Set<String> usedIds = new HashSet<>();
                final Set<String> headersIds = properties.getValues(prefix + "headers");
                for (String headerId : headersIds) {
                    if (usedIds.contains(headerId)) {
                        throw new ConfigurationNotValidException("while configuring action '" + id + "': header '" + headerId + "' duplicated");
                    } else {
                        usedIds.add(headerId);
                        CustomHeader header = headers.get(headerId);
                        if (header != null) {
                            customHeaders.add(header);
                        } else {
                            throw new ConfigurationNotValidException("while configuring action '" + id + "': header '" + headerId + "' does not exist");
                        }
                    }
                }

                ActionConfiguration action = new ActionConfiguration(id, actionType, director, file, code).setCustomHeaders(customHeaders);

                // Action of type REDIRECT
                String redirectLocation = properties.getString(prefix + "redirect.location", "");
                action.setRedirectLocation(redirectLocation);
                if (redirectLocation.isEmpty()) {
                    action.setRedirectProto(properties.getString(prefix + "redirect.proto", ""));
                    action.setRedirectHost(properties.getString(prefix + "redirect.host", ""));
                    action.setRedirectPort(properties.getInt(prefix + "redirect.port", -1));
                    action.setRedirectPath(properties.getString(prefix + "redirect.path", ""));
                    if (actionType.equals(ActionConfiguration.TYPE_REDIRECT)
                            && action.getRedirectProto().isEmpty()
                            && action.getRedirectHost().isEmpty()
                            && action.getRedirectPort() == -1
                            && action.getRedirectPath().isEmpty()) {
                        throw new ConfigurationNotValidException("while configuring action '" + id
                                + "': at least redirect.location or redirect.proto|.host|.port|.path have to be defined"
                        );
                    }
                }

                addAction(action);
                LOG.info("configured action {} type={} enabled:{} headers:{} redirect location:{} redirect proto:{} redirect host:{} redirect port:{} redirect path:{}",
                        id, actionType, enabled, headersIds, redirectLocation, action.getRedirectProto(), action.getRedirectHost(), action.getRedirectPort(), action.getRedirectPath());
            }
        }

        /*
         * BACKENDS
         */
        max = properties.findMaxIndexForPrefix("backend");
        for (int i = 0; i <= max; i++) {
            String prefix = "backend." + i + ".";
            String id = properties.getString(prefix + "id", "");
            if (!id.isEmpty()) {
                final boolean enabled = properties.getBoolean(prefix + "enabled", false);
                final String host = properties.getString(prefix + "host", "localhost");
                final int port = properties.getInt(prefix + "port", 8086);
                final String probePath = properties.getString(prefix + "probePath", "");
                final int safeCapacity = properties.getInt(prefix + "safeCapacity", DEFAULT_CAPACITY);
                final boolean ssl = properties.getBoolean(prefix + "ssl", false);
                final String caCertificatePath = properties.getString(prefix + "cacertificate", null);
                final String caCertificatePassword = properties.getString(prefix + "cacertificatepassword", null);
                LOG.info("configured backend {} {}:{} enabled={} capacity={} ssl={} caCertificate={} caCertificatePassword={}",
                         id, host, port, enabled, safeCapacity, ssl, caCertificatePath, caCertificatePassword != null ? "******" : null);
                if (enabled) {
                    addBackend(new BackendConfiguration(id, host, port, probePath, safeCapacity, ssl, caCertificatePath, caCertificatePassword));
                }
            }
        }

        /*
         * DIRECTORS
         */
        max = properties.findMaxIndexForPrefix("director");
        for (int i = 0; i <= max; i++) {
            String prefix = "director." + i + ".";
            String id = properties.getString(prefix + "id", "");
            if (!id.isEmpty()) {
                boolean enabled = properties.getBoolean(prefix + "enabled", false);
                LOG.info("configured director {} backends:{}, enabled:{}", id, backends, enabled);
                if (enabled) {
                    DirectorConfiguration config = new DirectorConfiguration(id);
                    for (String backendId : properties.getValues(prefix + "backends")) {
                        if (!backendId.equals(DirectorConfiguration.ALL_BACKENDS) && !this.backends.containsKey(backendId)) {
                            throw new ConfigurationNotValidException("while configuring director '" + id + "': backend '" + backendId + "' does not exist");
                        }
                        config.addBackend(backendId);
                    }
                    addDirector(config);
                }
            }
        }

        /*
         * ROUTES
         */
        max = properties.findMaxIndexForPrefix("route");
        for (int i = 0; i <= max; i++) {
            String prefix = "route." + i + ".";
            String id = properties.getString(prefix + "id", "");
            if (id.isEmpty()) {
                continue;
            }
            String matchingCondition = "";
            try {
                String action = properties.getString(prefix + "action", "");
                boolean enabled = properties.getBoolean(prefix + "enabled", false);
                matchingCondition = properties.getString(prefix + "match", "all");
                RequestMatcher matcher = new RequestMatchParser(matchingCondition).parse();
                LOG.info("configured route {} action: {} enabled: {} matcher: {}", id, action, enabled, matcher);
                RouteConfiguration config = new RouteConfiguration(id, action, enabled, matcher);
                // Error action
                String errorAction = properties.getString(prefix + "erroraction", "");
                if (!errorAction.isEmpty()) {
                    ActionConfiguration defined = actions.get(errorAction);
                    if (defined == null || !ActionConfiguration.TYPE_STATIC.equals(defined.getType())) {
                        throw new ConfigurationNotValidException("Error action for route " + id + " has to be defined and has to be type STATIC");
                    }
                }
                config.setErrorAction(errorAction);

                // Maintenance action
                String maintenanceAction = properties.getString(prefix + "maintenanceaction", "");
                if (!maintenanceAction.isEmpty()) {
                    ActionConfiguration defined = actions.get(maintenanceAction);
                    if (defined == null || !ActionConfiguration.TYPE_STATIC.equals(defined.getType())) {
                        throw new ConfigurationNotValidException("Maintenance action for route " + id + " has to be defined and has to be type STATIC");
                    }
                }
                config.setMaintenanceModeAction(maintenanceAction);
                addRoute(config);
            } catch (ParseException | ConfigurationNotValidException ex) {
                throw new ConfigurationNotValidException(
                        prefix + " unable to parse matching condition \"" + matchingCondition + "\" due to: " + ex
                );
            }
        }
    }

    private void addHeader(String id, String name, String value, String mode) throws ConfigurationNotValidException {
        final HeaderMode headerMode = switch (mode) {
            case "set" -> HeaderMode.SET;
            case "add" -> HeaderMode.ADD;
            case "remove" -> HeaderMode.REMOVE;
            default -> throw new ConfigurationNotValidException("invalid value of mode " + mode + " for header " + id);
        };
        if (headers.put(id, new CustomHeader(id, name, value, headerMode)) != null) {
            throw new ConfigurationNotValidException("header " + id + " is already configured");
        }
    }

    public void addDirector(DirectorConfiguration service) throws ConfigurationNotValidException {
        if (directors.put(service.getId(), service) != null) {
            throw new ConfigurationNotValidException("service " + service.getId() + " is already configured");
        }
    }

    public void addBackend(BackendConfiguration backend) throws ConfigurationNotValidException {
        if (backends.put(backend.id(), backend) != null) {
            throw new ConfigurationNotValidException("backend " + backend.id() + " is already configured");
        }
    }

    public void addAction(ActionConfiguration action) throws ConfigurationNotValidException {
        if (actions.put(action.getId(), action) != null) {
            throw new ConfigurationNotValidException("action " + action.getId() + " is already configured");
        }
    }

    public void addRoute(RouteConfiguration route) throws ConfigurationNotValidException {
        if (routes.stream().anyMatch(s -> s.getId().equalsIgnoreCase(route.getId()))) {
            throw new ConfigurationNotValidException("route " + route.getId() + " is already configured");
        }
        routes.add(route);
    }

    @Override
    public SequencedMap<String, BackendConfiguration> getBackends() {
        return Collections.unmodifiableSequencedMap(backends);
    }

    @Override
    public List<RouteConfiguration> getRoutes() {
        return routes;
    }

    @Override
    public List<ActionConfiguration> getActions() {
        return new ArrayList<>(actions.values());
    }

    @Override
    public SequencedMap<String, DirectorConfiguration> getDirectors() {
        return Collections.unmodifiableSequencedMap(directors);
    }

    @Override
    public List<CustomHeader> getHeaders() {
        return new ArrayList<>(headers.values());
    }

    public String getForceDirectorParameter() {
        return forceDirectorParameter;
    }

    public String getForceBackendParameter() {
        return forceBackendParameter;
    }

}
