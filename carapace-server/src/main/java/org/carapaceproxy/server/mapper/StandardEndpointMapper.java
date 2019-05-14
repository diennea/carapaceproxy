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
package org.carapaceproxy.server.mapper;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.EndpointMapper;
import org.carapaceproxy.MapResult;
import org.carapaceproxy.MapResult.Action;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.RequestHandler;
import static org.carapaceproxy.server.RequestHandler.PROPERTY_URI;
import static org.carapaceproxy.server.StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
import static org.carapaceproxy.server.StaticContentsManager.DEFAULT_NOT_FOUND;
import static org.carapaceproxy.server.StaticContentsManager.IN_MEMORY_RESOURCE;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.certiticates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.BackendSelector;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.DirectorConfiguration;
import static org.carapaceproxy.server.config.DirectorConfiguration.ALL_BACKENDS;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.mapper.requestmatcher.RegexpRequestMatcher;
import org.carapaceproxy.server.filters.UrlEncodedQueryString;
import org.carapaceproxy.server.mapper.CustomHeader.HeaderMode;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;

/**
 * Standard Endpoint mapping
 */
public class StandardEndpointMapper extends EndpointMapper {

    private final Map<String, BackendConfiguration> backends = new HashMap<>();
    private final Map<String, DirectorConfiguration> directors = new HashMap<>();
    private final List<String> allbackendids = new ArrayList<>();
    private final List<RouteConfiguration> routes = new ArrayList<>();
    private final Map<String, ActionConfiguration> actions = new HashMap<>();
    public final Map<String, CustomHeader> headers = new HashMap();
    private final BackendSelector backendSelector;
    private String defaultNotFoundAction = "not-found";
    private String defaultInternalErrorAction = "internal-error";
    private String forceDirectorParameter = "x-director";
    private String forceBackendParameter = "x-backend";

    private static final int MAX_IDS = 200;
    private static final Logger LOG = Logger.getLogger(StandardEndpointMapper.class.getName());
    private static final String ACME_CHALLENGE_URI_PATTERN = "/\\.well-known/acme-challenge/";
    private DynamicCertificatesManager dynamicCertificateManger;

    public static final String DEBUGGING_HEADER_DEFAULT_NAME = "X-Proxy-Path";
    private String debuggingHeaderName = DEBUGGING_HEADER_DEFAULT_NAME;
    private boolean debuggingHeaderEnabled = false;

    public StandardEndpointMapper(BackendSelector backendSelector) {
        this.backendSelector = backendSelector;
    }

    @Override
    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {

        addAction(new ActionConfiguration("proxy-all", ActionConfiguration.TYPE_PROXY, DirectorConfiguration.DEFAULT, null, -1));
        addAction(new ActionConfiguration("cache-if-possible", ActionConfiguration.TYPE_CACHE, DirectorConfiguration.DEFAULT, null, -1));
        addAction(new ActionConfiguration("not-found", ActionConfiguration.TYPE_STATIC, null, DEFAULT_NOT_FOUND, 404));
        addAction(new ActionConfiguration("internal-error", ActionConfiguration.TYPE_STATIC, null, DEFAULT_INTERNAL_SERVER_ERROR, 500));

        // Route+Action configuration for Let's Encrypt ACME challenging
        addAction(new ActionConfiguration("acme-challenge", ActionConfiguration.TYPE_ACME_CHALLENGE, null, null, HttpResponseStatus.OK.code()));
        addRoute(new RouteConfiguration("acme-challenge", "acme-challenge", true, new RegexpRequestMatcher(PROPERTY_URI, ".*" + ACME_CHALLENGE_URI_PATTERN + ".*")));

        this.defaultNotFoundAction = properties.getProperty("default.action.notfound", "not-found");
        LOG.info("configured default.action.notfound=" + defaultNotFoundAction);
        this.defaultInternalErrorAction = properties.getProperty("default.action.internalerror", "internal-error");
        LOG.info("configured default.action.internalerror=" + defaultInternalErrorAction);
        this.forceDirectorParameter = properties.getProperty("mapper.forcedirector.parameter", forceDirectorParameter);
        LOG.info("configured mapper.forcedirector.parameter=" + forceDirectorParameter);
        this.forceBackendParameter = properties.getProperty("mapper.forcebackend.parameter", forceBackendParameter);
        LOG.info("configured mapper.forcebackend.parameter=" + forceBackendParameter);
        // To add custom debugging header for request choosen mapping-path
        this.debuggingHeaderEnabled = Boolean.parseBoolean(properties.getProperty("mapper.debug", "false"));
        LOG.info("configured mapper.debug=" + debuggingHeaderEnabled);
        this.debuggingHeaderName = properties.getProperty("mapper.debug.name", DEBUGGING_HEADER_DEFAULT_NAME);
        LOG.info("configured mapper.debug.name=" + debuggingHeaderName);

        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "header." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            String name = properties.getProperty(prefix + "name", "");
            if (!id.isEmpty() && !name.isEmpty()) {
                String value = properties.getProperty(prefix + "value", "");
                String mode = properties.getProperty(prefix + "mode", "add").toLowerCase().trim();
                addHeader(id, name, value, mode);
                LOG.info("configured header " + id + " name:" + name + ", value:" + value);
            }
        }

        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "action." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
            if (!id.isEmpty() && enabled) {
                String action = properties.getProperty(prefix + "type", ActionConfiguration.TYPE_PROXY);
                String file = properties.getProperty(prefix + "file", "");
                String director = properties.getProperty(prefix + "director", DirectorConfiguration.DEFAULT);
                int code = Integer.parseInt(properties.getProperty(prefix + "code", "-1"));
                String headersIds = properties.getProperty(prefix + "headers", "").trim();
                List<CustomHeader> customHeaders = new ArrayList();
                if (!headersIds.isEmpty()) {
                    String[] _headersIds = headersIds.split(",");
                    Set<String> usedIds = new HashSet();
                    for (String headerId : _headersIds) {
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
                }

                ActionConfiguration _action = new ActionConfiguration(id, action, director, file, code)
                        .setCustomHeaders(customHeaders);
                String redirectLocation = properties.getProperty(prefix + "redirect.location", "");
                _action.setRedirectLocation(redirectLocation);
                if (redirectLocation.isEmpty()) {
                    _action.setRedirectProto(properties.getProperty(prefix + "redirect.proto", ""));
                    _action.setRedirectHost(properties.getProperty(prefix + "redirect.host", ""));
                    _action.setRedirectPort(Integer.parseInt(properties.getProperty(prefix + "redirect.port", "-1")));
                    _action.setRedirectPath(properties.getProperty(prefix + "redirect.path", ""));
                    if (action.equals(ActionConfiguration.TYPE_REDIRECT) && _action.getRedirectProto().isEmpty() && _action.getRedirectHost().isEmpty()
                            && _action.getRedirectPort() == -1 && _action.getRedirectPath().isEmpty()) {
                        throw new ConfigurationNotValidException("while configuring action '" + id
                                + "': at least redirect.location or redirect.proto|.host|.port|.path have to be defined"
                        );
                    }
                }

                addAction(_action);
                LOG.info("configured action " + id + " type=" + action + " enabled:" + enabled + " headers:" + headersIds
                        + " redirect location:" + redirectLocation + " redirect proto:" + _action.getRedirectProto()
                        + " redirect host:" + _action.getRedirectHost() + " redirect port:" + _action.getRedirectPort()
                        + " redirect path:" + _action.getRedirectPath()
                );
            }
        }

        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "backend." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            if (!id.isEmpty()) {
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
                String host = properties.getProperty(prefix + "host", "localhost");
                int port = Integer.parseInt(properties.getProperty(prefix + "port", "8086"));
                String probePath = properties.getProperty(prefix + "probePath", null);
                LOG.info("configured backend " + id + " " + host + ":" + port + " enabled:" + enabled);
                if (enabled) {
                    BackendConfiguration config = new BackendConfiguration(id, host, port, probePath);
                    addBackend(config);
                }
            }
        }

        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "director." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            if (!id.isEmpty()) {
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
                String backends = properties.getProperty(prefix + "backends", "");
                LOG.info("configured director " + id + " backends:" + backends + ", enabled:" + enabled);
                if (enabled) {
                    DirectorConfiguration config = new DirectorConfiguration(id);
                    String[] backendids = backends.split(",");
                    for (String backendId : backendids) {
                        if (!backendId.equals(DirectorConfiguration.ALL_BACKENDS) && !this.backends.containsKey(backendId)) {
                            throw new ConfigurationNotValidException("while configuring director '" + id + "': backend '" + backendId + "' does not exist");
                        }
                        config.addBackend(backendId);
                    }
                    addDirector(config);
                }
            }
        }
        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "route." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            if (!id.isEmpty()) {
                String action = properties.getProperty(prefix + "action", "");
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
                String matchingCondition = properties.getProperty(prefix + "match", "all").trim();
                try {
                    RequestMatcher matcher = new RequestMatchParser(matchingCondition).parse();
                    LOG.log(Level.INFO, "configured route {0} action: {1} enabled: {2} matcher: {3}", new Object[]{id, action, enabled, matcher});
                    RouteConfiguration config = new RouteConfiguration(id, action, enabled, matcher);
                    addRoute(config);
                } catch (ParseException | ConfigurationNotValidException ex) {
                    throw new ConfigurationNotValidException(
                            prefix + " unable to parse matching condition \"" + matchingCondition + "\" due to: " + ex
                    );
                }
            }
        }
    }

    private final class RandomBackendSelector implements BackendSelector {

        @Override
        public List<String> selectBackends(String userId, String sessionId, String director) {
            DirectorConfiguration directorConfig = directors.get(director);
            if (directorConfig == null) {
                LOG.log(Level.SEVERE, "Director '" + director + "' not configured, while handling request  + userId=" + userId + " sessionId=" + sessionId);
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

    public StandardEndpointMapper() {
        this.backendSelector = new RandomBackendSelector();
    }

    private void addHeader(String id, String name, String value, String mode) throws ConfigurationNotValidException {
        HeaderMode _mode = HeaderMode.HEADER_MODE_ADD;
        switch (mode) {
            case "set":
                _mode = HeaderMode.HEADER_MODE_SET;
                break;
            case "add":
                _mode = HeaderMode.HEADER_MODE_ADD;
                break;
            case "remove":
                _mode = HeaderMode.HEADER_MODE_REMOVE;
                break;
            default:
                throw new ConfigurationNotValidException("invalid value of mode " + mode + " for header " + id);
        }

        if (headers.put(id, new CustomHeader(name, value, _mode)) != null) {
            throw new ConfigurationNotValidException("header " + id + " is already configured");
        }
    }

    public void addDirector(DirectorConfiguration service) throws ConfigurationNotValidException {
        if (directors.put(service.getId(), service) != null) {
            throw new ConfigurationNotValidException("service " + service.getId() + " is already configured");
        }
    }

    public void addBackend(BackendConfiguration backend) throws ConfigurationNotValidException {
        if (backends.put(backend.getId(), backend) != null) {
            throw new ConfigurationNotValidException("backend " + backend.getId() + " is already configured");
        }
        allbackendids.add(backend.getId());
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
        return new ArrayList(actions.values());
    }

    @Override
    public List<DirectorConfiguration> getDirectors() {
        return new ArrayList(directors.values());
    }

    @Override
    public void setDynamicCertificateManager(DynamicCertificatesManager manager) {
        this.dynamicCertificateManger = manager;
    }

    @Override
    public MapResult map(HttpRequest request, String userId, String sessionId, BackendHealthManager backendHealthManager, RequestHandler requestHandler) {
        boolean somethingMatched = false;
        for (RouteConfiguration route : routes) {
            if (!route.isEnabled()) {
                continue;
            }
            boolean matchResult = route.matches(requestHandler);
            if (LOG.isLoggable(Level.FINER)) {
                LOG.finer("route " + route.getId() + ", map " + request.uri() + " -> " + matchResult);
            }
            if (matchResult) {
                ActionConfiguration action = actions.get(route.getAction());
                if (action == null) {
                    LOG.info("no action '" + route.getAction() + "' -> not-found for " + request.uri() + ", valid " + actions.keySet());
                    return MapResult.NOT_FOUND(route.getId());
                }

                if (ActionConfiguration.TYPE_REDIRECT.equals(action.getType())) {
                    return new MapResult(action.getRedirectHost(), action.getRedirectPort(), MapResult.Action.REDIRECT, route.getId())
                            .setRedirectLocation(action.getRedirectLocation())
                            .setRedirectProto(action.getRedirectProto())
                            .setRedirectPath(action.getRedirectPath())
                            .setErrorcode(action.getErrorcode())
                            .setCustomHeaders(action.getCustomHeaders());
                }
                if (ActionConfiguration.TYPE_STATIC.equals(action.getType())) {
                    return new MapResult(null, -1, MapResult.Action.STATIC, route.getId())
                            .setResource(action.getFile())
                            .setErrorcode(action.getErrorcode())
                            .setCustomHeaders(action.getCustomHeaders());
                }
                if (ActionConfiguration.TYPE_ACME_CHALLENGE.equals(action.getType())) {
                    if (this.dynamicCertificateManger != null) {
                        String tokenName = request.uri().replaceFirst(".*" + ACME_CHALLENGE_URI_PATTERN, "");
                        String tokenData = dynamicCertificateManger.getChallengeToken(tokenName);
                        if (tokenData == null) {
                            return MapResult.NOT_FOUND(route.getId());
                        }
                        return new MapResult(null, -1, MapResult.Action.ACME_CHALLENGE, route.getId())
                                .setResource(IN_MEMORY_RESOURCE + tokenData)
                                .setErrorcode(action.getErrorcode());
                    } else {
                        return MapResult.INTERNAL_ERROR(route.getId());
                    }
                }
                UrlEncodedQueryString queryString = requestHandler.getQueryString();
                String director = action.getDirector();
                String forceBackendParameterValue = queryString.get(forceBackendParameter);

                final List<String> selectedBackends;
                if (forceBackendParameterValue != null) {
                    LOG.log(Level.INFO, "forcing backend = {0} for {1}", new Object[]{forceBackendParameterValue, request.uri()});
                    selectedBackends = Collections.singletonList(forceBackendParameterValue);
                } else {
                    String forceDirectorParameterValue = queryString.get(forceDirectorParameter);
                    if (forceDirectorParameterValue != null) {
                        director = forceDirectorParameterValue;
                        LOG.log(Level.INFO, "forcing director = {0} for {1}", new Object[]{director, request.uri()});
                    }
                    selectedBackends = backendSelector.selectBackends(userId, sessionId, director);
                }
                somethingMatched = somethingMatched | !selectedBackends.isEmpty();

                LOG.log(Level.FINEST, "selected {0} backends for {1}, director is {2}", new Object[]{selectedBackends, request.uri(), director});
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
                            return MapResult.NOT_FOUND(route.getId());
                    }

                    BackendConfiguration backend = this.backends.get(backendId);
                    if (backend != null && backendHealthManager.isAvailable(backendId)) {
                        List<CustomHeader> customHeaders = action.getCustomHeaders();
                        if (this.debuggingHeaderEnabled) {
                            customHeaders = new ArrayList(customHeaders);
                            String routingPath = route.getId() + ";"
                                    + action.getId() + ";"
                                    + action.getDirector() + ";"
                                    + backendId;
                            customHeaders.add(new CustomHeader(debuggingHeaderName, routingPath, HeaderMode.HEADER_MODE_ADD));
                        }
                        return new MapResult(backend.getHost(), backend.getPort(), selectedAction, route.getId())
                                .setCustomHeaders(customHeaders);
                    }
                }
            }
        }
        if (somethingMatched) {
            return MapResult.INTERNAL_ERROR(MapResult.NO_ROUTE);
        } else {
            return MapResult.NOT_FOUND(MapResult.NO_ROUTE);
        }
    }

    public String getDefaultNotFoundAction() {
        return defaultNotFoundAction;
    }

    public String getDefaultInternalErrorAction() {
        return defaultInternalErrorAction;
    }

    public String getForceDirectorParameter() {
        return forceDirectorParameter;
    }

    public String getForceBackendParameter() {
        return forceBackendParameter;
    }

}
