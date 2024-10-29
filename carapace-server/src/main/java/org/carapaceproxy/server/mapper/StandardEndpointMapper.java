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
import static org.carapaceproxy.server.config.DirectorConfiguration.ALL_BACKENDS;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.BackendSelector;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.filters.UrlEncodedQueryString;
import org.carapaceproxy.server.mapper.CustomHeader.HeaderMode;
import org.carapaceproxy.server.mapper.MapResult.Action;
import org.carapaceproxy.server.mapper.requestmatcher.RegexpRequestMatcher;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;

/**
 * Standard Endpoint mapping
 */
public class StandardEndpointMapper extends EndpointMapper {

    public static final String ACME_CHALLENGE_ROUTE_ACTION_ID = "acme-challenge";
    private final Map<String, BackendConfiguration> backends = new HashMap<>(); // wiped out whenever a new configuration is applied
    private final Map<String, DirectorConfiguration> directors = new HashMap<>();
    private final List<String> allbackendids = new ArrayList<>();
    private final List<RouteConfiguration> routes = new ArrayList<>();
    private final Map<String, ActionConfiguration> actions = new HashMap<>();
    public final Map<String, CustomHeader> headers = new HashMap<>();
    private final BackendSelector backendSelector;
    private String defaultNotFoundAction = "not-found";
    private String defaultInternalErrorAction = "internal-error";
    private String forceDirectorParameter = "x-director";
    private String forceBackendParameter = "x-backend";
    private String defaultMaintenanceAction = "maintenance";
    private String defaultBadRequestAction = "bad-request";
    private String defaultServiceUnavailable = "service-unavailable";

    private static final Logger LOG = Logger.getLogger(StandardEndpointMapper.class.getName());
    private static final String ACME_CHALLENGE_URI_PATTERN = "/\\.well-known/acme-challenge/";

    public static final String DEBUGGING_HEADER_DEFAULT_NAME = "X-Proxy-Path";
    public static final String DEBUGGING_HEADER_ID = "mapper-debug";
    private String debuggingHeaderName = DEBUGGING_HEADER_DEFAULT_NAME;
    private boolean debuggingHeaderEnabled = false;

    public StandardEndpointMapper(BackendSelector backendSelector) {
        this.backendSelector = backendSelector;
    }

    public StandardEndpointMapper() {
        this.backendSelector = new RandomBackendSelector();
    }

    private final class RandomBackendSelector implements BackendSelector {

        @Override
        public List<String> selectBackends(String userId, String sessionId, String director) {
            DirectorConfiguration directorConfig = directors.get(director);
            if (directorConfig == null) {
                LOG.log(Level.SEVERE, "Director ''{0}'' not configured, while handling request  + userId={1} sessionId={2}", new Object[]{director, userId, sessionId});
                return Collections.emptyList();
            }
            if (directorConfig.getBackends().contains(ALL_BACKENDS)) {
                ArrayList<String> result = new ArrayList<>(allbackendids);
                Collections.shuffle(result);
                return result;
            } else if (directorConfig.getBackends().size() == 1) {
                return directorConfig.getBackends();
            } else {
                ArrayList<String> result = new ArrayList<>(directorConfig.getBackends());
                Collections.shuffle(result);
                return result;
            }
        }

    }

    @Override
    public MapResult map(ProxyRequest request) {
        // If the HOST header is null (when on HTTP/1.1 or less), then return bad request
        // https://www.rfc-editor.org/rfc/rfc2616#page-38
        if (request.getRequestHostname() == null || request.getRequestHostname().isBlank()) {
            if (LOG.isLoggable(Level.FINER)) {
                LOG.log(Level.FINER, "Request " + request.getUri() + " header host is null or empty");
            }
            return MapResult.badRequest();
        } else if (!request.isValidHostAndPort(request.getRequestHostname())) {  //Invalid header host
            if (LOG.isLoggable(Level.FINER)) {
                LOG.log(Level.FINER, "Invalid header host {0} for request {1}", new Object[]{request.getRequestHostname(), request.getUri()});
            }
            return MapResult.badRequest();
        }

        for (RouteConfiguration route : routes) {
            if (!route.isEnabled()) {
                continue;
            }

            boolean matchResult = route.matches(request);
            if (LOG.isLoggable(Level.FINER)) {
                LOG.log(Level.FINER, "route {0}, map {1} -> {2}", new Object[]{route.getId(), request.getUri(), matchResult});
            }

            if (matchResult) {
                if (parent.getCurrentConfiguration().isMaintenanceModeEnabled()) {
                    if (LOG.isLoggable(Level.FINER)) {
                        LOG.log(Level.FINER, "Maintenance mode is enable: request uri: {0}", request.getUri());
                    }
                    return MapResult.maintenanceMode(route.getId());
                }

                ActionConfiguration action = actions.get(route.getAction());
                if (action == null) {
                    LOG.log(Level.INFO, "no action ''{0}'' -> not-found for {1}, valid {2}", new Object[]{route.getAction(), request.getUri(), actions.keySet()});
                    return MapResult.internalError(route.getId());
                }
                if (ActionConfiguration.TYPE_REDIRECT.equals(action.getType())) {
                    return MapResult.builder()
                            .host(action.getRedirectHost())
                            .port(action.getRedirectPort())
                            .action(MapResult.Action.REDIRECT)
                            .routeId(route.getId())
                            .redirectLocation(action.getRedirectLocation())
                            .redirectProto(action.getRedirectProto())
                            .redirectPath(action.getRedirectPath())
                            .errorCode(action.getErrorCode())
                            .customHeaders(action.getCustomHeaders())
                            .build();
                }
                if (ActionConfiguration.TYPE_STATIC.equals(action.getType())) {
                    return MapResult.builder()
                            .action(MapResult.Action.STATIC)
                            .routeId(route.getId())
                            .resource(action.getFile())
                            .errorCode(action.getErrorCode())
                            .customHeaders(action.getCustomHeaders())
                            .build();
                }
                if (ActionConfiguration.TYPE_ACME_CHALLENGE.equals(action.getType())) {
                    String tokenName = request.getUri().replaceFirst(".*" + ACME_CHALLENGE_URI_PATTERN, "");
                    String tokenData = parent.getDynamicCertificatesManager().getChallengeToken(tokenName);
                    if (tokenData == null) {
                        return MapResult.notFound(route.getId());
                    }
                    return MapResult.builder()
                            .action(MapResult.Action.ACME_CHALLENGE)
                            .routeId(route.getId())
                            .resource(IN_MEMORY_RESOURCE + tokenData)
                            .errorCode(action.getErrorCode())
                            .build();
                }
                UrlEncodedQueryString queryString = request.getQueryString();
                String director = action.getDirector();
                String forceBackendParameterValue = queryString.get(forceBackendParameter);

                final List<String> selectedBackends;
                if (forceBackendParameterValue != null) {
                    LOG.log(Level.INFO, "forcing backend = {0} for {1}", new Object[]{forceBackendParameterValue, request.getUri()});
                    selectedBackends = Collections.singletonList(forceBackendParameterValue);
                } else {
                    String forceDirectorParameterValue = queryString.get(forceDirectorParameter);
                    if (forceDirectorParameterValue != null) {
                        director = forceDirectorParameterValue;
                        LOG.log(Level.INFO, "forcing director = {0} for {1}", new Object[]{director, request.getUri()});
                    }
                    selectedBackends = backendSelector.selectBackends(request.getUserId(), request.getSessionId(), director);
                }

                LOG.log(Level.FINEST, "selected {0} backends for {1}, director is {2}", new Object[]{selectedBackends, request.getUri(), director});
                for (String backendId : selectedBackends) {
                    Action selectedAction;
                    switch (action.getType()) {
                        case ActionConfiguration.TYPE_PROXY:
                            selectedAction = MapResult.Action.PROXY;
                            break;
                        case ActionConfiguration.TYPE_CACHE:
                            selectedAction = MapResult.Action.CACHE;
                            break;
                        default:
                            return MapResult.internalError(route.getId());
                    }

                    BackendConfiguration backend = this.backends.get(backendId);
                    if (backend != null && parent.getBackendHealthManager().isAvailable(backend.hostPort().toString())) {
                        List<CustomHeader> customHeaders = action.getCustomHeaders();
                        if (this.debuggingHeaderEnabled) {
                            customHeaders = new ArrayList<>(customHeaders);
                            String routingPath = route.getId() + ";"
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
                                .build();
                    }
                }
                // none of selected backends available
                // return service unavailable if all backend is unavailable
                if (!selectedBackends.isEmpty()) {
                    return MapResult.serviceUnavailable(route.getId());
                }
            }
        }
        // no one route matched
        return MapResult.notFound(MapResult.NO_ROUTE);
    }

    private ActionConfiguration getErrorActionConfiguration(String routeId, String defaultAction) {
        // Attempt to find a route-specific configuration first
        Optional<RouteConfiguration> config = routes.stream()
                .filter(r -> r.getId().equalsIgnoreCase(routeId))
                .findFirst();
        if (config.isPresent()) {
            String action = config.get().getErrorAction();
            if (action != null && actions.containsKey(action)) {
                return actions.get(action);
            }
        }
        // If no route-specific configuration or action is found, fallback to the global default
        if (defaultAction != null && actions.containsKey(defaultAction)) {
            return actions.get(defaultAction);
        }
        // Return null if no appropriate action configuration is found
        return null;
    }


    @Override
    public SimpleHTTPResponse mapInternalError(String routeid) {
        ActionConfiguration errorAction = getErrorActionConfiguration(routeid, defaultInternalErrorAction);
        if (errorAction != null) {
            return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
        }
        // fallback
        return super.mapInternalError(routeid);
    }

    @Override
    public SimpleHTTPResponse mapServiceUnavailableError(String routeId) {
        ActionConfiguration errorAction = getErrorActionConfiguration(routeId, defaultServiceUnavailable);
        if (errorAction != null) {
            return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
        }
        // fallback
        return super.mapServiceUnavailableError(routeId);
    }

    @Override
    public SimpleHTTPResponse mapMaintenanceMode(String routeId) {
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
        if (maintenanceAction == null && defaultMaintenanceAction != null) {
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
        if (defaultBadRequestAction != null) {
            ActionConfiguration errorAction = actions.get(defaultBadRequestAction);
            if (errorAction != null) {
                return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
            }
        }
        // fallback
        return super.mapBadRequest();
    }

    @Override
    public SimpleHTTPResponse mapPageNotFound(String routeId) {
        // custom global
        if (defaultNotFoundAction != null) {
            ActionConfiguration errorAction = actions.get(defaultNotFoundAction);
            if (errorAction != null) {
                return new SimpleHTTPResponse(errorAction.getErrorCode(), errorAction.getFile(), errorAction.getCustomHeaders());
            }
        }
        // fallback
        return super.mapPageNotFound(routeId);
    }

    @Override
    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {

        addAction(new ActionConfiguration("proxy-all", ActionConfiguration.TYPE_PROXY, DirectorConfiguration.DEFAULT, null, -1));
        addAction(new ActionConfiguration("cache-if-possible", ActionConfiguration.TYPE_CACHE, DirectorConfiguration.DEFAULT, null, -1));
        addAction(new ActionConfiguration("not-found", ActionConfiguration.TYPE_STATIC, null, DEFAULT_NOT_FOUND, 404));
        addAction(new ActionConfiguration("internal-error", ActionConfiguration.TYPE_STATIC, null, DEFAULT_INTERNAL_SERVER_ERROR, 500));
        addAction(new ActionConfiguration("maintenance", ActionConfiguration.TYPE_STATIC, null, DEFAULT_MAINTENANCE_MODE_ERROR, 500));

        // Route+Action configuration for Let's Encrypt ACME challenging
        addAction(new ActionConfiguration(
                ACME_CHALLENGE_ROUTE_ACTION_ID, ActionConfiguration.TYPE_ACME_CHALLENGE,
                null, null, HttpResponseStatus.OK.code()
        ));
        addRoute(new RouteConfiguration(
                ACME_CHALLENGE_ROUTE_ACTION_ID, ACME_CHALLENGE_ROUTE_ACTION_ID, true,
                new RegexpRequestMatcher(ProxyRequest.PROPERTY_URI, ".*" + ACME_CHALLENGE_URI_PATTERN + ".*")
        ));

        this.defaultNotFoundAction = properties.getString("default.action.notfound", "not-found");
        LOG.log(Level.INFO, "configured default.action.notfound={0}", defaultNotFoundAction);
        this.defaultInternalErrorAction = properties.getString("default.action.internalerror", "internal-error");
        LOG.log(Level.INFO, "configured default.action.internalerror={0}", defaultInternalErrorAction);
        this.defaultMaintenanceAction = properties.getString("default.action.maintenance", "maintenance");
        LOG.log(Level.INFO, "configured default.action.maintenance={0}", defaultMaintenanceAction);
        this.defaultBadRequestAction = properties.getString("default.action.badrequest", "bad-request");
        LOG.log(Level.INFO, "configured default.action.badrequest={0}", defaultBadRequestAction);
        this.defaultServiceUnavailable = properties.getString("default.action.serviceunavailable", "service-unavailable");
        LOG.log(Level.INFO, "configured default.action.serviceunavailable={0}", defaultServiceUnavailable);
        this.forceDirectorParameter = properties.getString("mapper.forcedirector.parameter", forceDirectorParameter);
        LOG.log(Level.INFO, "configured mapper.forcedirector.parameter={0}", forceDirectorParameter);
        this.forceBackendParameter = properties.getString("mapper.forcebackend.parameter", forceBackendParameter);
        LOG.log(Level.INFO, "configured mapper.forcebackend.parameter={0}", forceBackendParameter);
        // To add custom debugging header for request choosen mapping-path
        this.debuggingHeaderEnabled = properties.getBoolean("mapper.debug", false);
        LOG.log(Level.INFO, "configured mapper.debug={0}", debuggingHeaderEnabled);
        this.debuggingHeaderName = properties.getString("mapper.debug.name", DEBUGGING_HEADER_DEFAULT_NAME);
        LOG.log(Level.INFO, "configured mapper.debug.name={0}", debuggingHeaderName);

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
                LOG.log(Level.INFO, "configured header {0} name:{1}, value:{2}", new Object[]{id, name, value});
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
                String action = properties.getString(prefix + "type", ActionConfiguration.TYPE_PROXY);
                String file = properties.getString(prefix + "file", "");
                String director = properties.getString(prefix + "director", DirectorConfiguration.DEFAULT);
                int code = properties.getInt(prefix + "code", -1);
                // Headers
                List<CustomHeader> customHeaders = new ArrayList<>();
                Set<String> usedIds = new HashSet<>();
                final var headersIds = properties.getValues(prefix + "headers");
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

                ActionConfiguration _action = new ActionConfiguration(id, action, director, file, code).setCustomHeaders(customHeaders);

                // Action of type REDIRECT
                String redirectLocation = properties.getString(prefix + "redirect.location", "");
                _action.setRedirectLocation(redirectLocation);
                if (redirectLocation.isEmpty()) {
                    _action.setRedirectProto(properties.getString(prefix + "redirect.proto", ""));
                    _action.setRedirectHost(properties.getString(prefix + "redirect.host", ""));
                    _action.setRedirectPort(properties.getInt(prefix + "redirect.port", -1));
                    _action.setRedirectPath(properties.getString(prefix + "redirect.path", ""));
                    if (action.equals(ActionConfiguration.TYPE_REDIRECT) && _action.getRedirectProto().isEmpty() && _action.getRedirectHost().isEmpty()
                            && _action.getRedirectPort() == -1 && _action.getRedirectPath().isEmpty()) {
                        throw new ConfigurationNotValidException("while configuring action '" + id
                                + "': at least redirect.location or redirect.proto|.host|.port|.path have to be defined"
                        );
                    }
                }

                addAction(_action);
                LOG.log(Level.INFO, "configured action {0} type={1} enabled:{2} headers:{3} redirect location:{4} redirect proto:{5} redirect host:{6} redirect port:{7} redirect path:{8}",
                        new Object[]{id, action, enabled, headersIds, redirectLocation, _action.getRedirectProto(), _action.getRedirectHost(), _action.getRedirectPort(), _action.getRedirectPath()});
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
                boolean enabled = properties.getBoolean(prefix + "enabled", false);
                String host = properties.getString(prefix + "host", "localhost");
                int port = properties.getInt(prefix + "port", 8086);
                String probePath = properties.getString(prefix + "probePath", "");
                LOG.log(Level.INFO, "configured backend {0} {1}:{2} enabled:{3}", new Object[]{id, host, port, enabled});
                if (enabled) {
                    BackendConfiguration config = new BackendConfiguration(id, host, port, probePath);
                    addBackend(config);
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
                LOG.log(Level.INFO, "configured director {0} backends:{1}, enabled:{2}", new Object[]{id, backends, enabled});
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
                LOG.log(Level.INFO, "configured route {0} action: {1} enabled: {2} matcher: {3}", new Object[]{id, action, enabled, matcher});
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
                //Maintenance action
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
        final HeaderMode _mode = switch (mode) {
            case "set" -> HeaderMode.SET;
            case "add" -> HeaderMode.ADD;
            case "remove" -> HeaderMode.REMOVE;
            default -> throw new ConfigurationNotValidException("invalid value of mode " + mode + " for header " + id);
        };

        if (headers.put(id, new CustomHeader(id, name, value, _mode)) != null) {
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
        allbackendids.add(backend.id());
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
    public Map<String, BackendConfiguration> getBackends() {
        return backends;
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
    public List<DirectorConfiguration> getDirectors() {
        return new ArrayList<>(directors.values());
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
