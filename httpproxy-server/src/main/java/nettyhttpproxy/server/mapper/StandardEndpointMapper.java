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
package nettyhttpproxy.server.mapper;

import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.MapResult;
import static nettyhttpproxy.server.StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
import static nettyhttpproxy.server.StaticContentsManager.DEFAULT_NOT_FOUND;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.config.ActionConfiguration;
import nettyhttpproxy.server.config.BackendConfiguration;
import nettyhttpproxy.server.config.BackendSelector;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.MatchAllRequestMatcher;
import nettyhttpproxy.server.config.RequestMatcher;
import nettyhttpproxy.server.config.RouteConfiguration;
import nettyhttpproxy.server.config.RoutingKey;
import nettyhttpproxy.server.config.DirectorConfiguration;
import nettyhttpproxy.server.config.URIRequestMatcher;

/**
 * Standard Endpoint mapping
 */
public class StandardEndpointMapper extends EndpointMapper {

    private final Map<String, BackendConfiguration> backends = new HashMap<>();
    private final Map<String, DirectorConfiguration> directors = new HashMap<>();
    private final List<String> allbackendids = new ArrayList<>();
    private final List<RouteConfiguration> routes = new ArrayList<>();
    private final Map<String, ActionConfiguration> actions = new HashMap<>();
    private final BackendSelector backendSelector;
    private String defaultNotFoundAction = "not-found";
    private String defaultInternalErrorAction = "internal-error";

    private static final int MAX_IDS = 200;
    private static final Logger LOG = Logger.getLogger(StandardEndpointMapper.class.getName());

    public StandardEndpointMapper(BackendSelector backendSelector) {
        this.backendSelector = backendSelector;
    }

    public void configure(Properties properties) throws ConfigurationNotValidException {

        LOG.info("configured build-in action id=proxy-all");
        addAction(new ActionConfiguration("proxy-all", ActionConfiguration.TYPE_PROXY, null, -1));
        addAction(new ActionConfiguration("cache-if-possible", ActionConfiguration.TYPE_CACHE, null, -1));
        addAction(new ActionConfiguration("not-found", ActionConfiguration.TYPE_STATIC, DEFAULT_NOT_FOUND, 404));
        addAction(new ActionConfiguration("internal-error", ActionConfiguration.TYPE_STATIC, DEFAULT_INTERNAL_SERVER_ERROR, 500));

        this.defaultNotFoundAction = properties.getProperty("default.action.notfound", "not-found");
        LOG.info("configured default.action.notfound=" + defaultNotFoundAction);
        this.defaultInternalErrorAction = properties.getProperty("default.action.internalerror", "internal-error");
        LOG.info("configured default.action.internalerror=" + defaultInternalErrorAction);

        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "action." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            if (!id.isEmpty()) {
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
                String action = properties.getProperty(prefix + "type", "proxy");
                String file = properties.getProperty(prefix + "file", "");
                int code = Integer.parseInt(properties.getProperty(prefix + "code", "-1"));
                LOG.info("configured action " + id + " type=" + action + " enabled:" + enabled);
                if (enabled) {
                    ActionConfiguration config = new ActionConfiguration(id, action, file, code);
                    addAction(config);
                }
            }
        }

        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "backend." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            if (!id.isEmpty()) {
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
                String host = properties.getProperty(prefix + "host", "localhost");
                int port = Integer.parseInt(properties.getProperty(prefix + "port", "8086"));
                LOG.info("configured backend " + id + " " + host + ":" + port + " enabled:" + enabled);
                if (enabled) {
                    BackendConfiguration config = new BackendConfiguration(id, host, port, "/test.html");
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
                        if (!this.backends.containsKey(backendId)) {
                            throw new ConfigurationNotValidException("while configuring director '" + id + "': backend '" + backendId + "' does not exist");
                        }
                        config.addBackend(id);
                    }
                    addService(config);
                }
            }
        }
        for (int i = 0; i < MAX_IDS; i++) {
            String prefix = "route." + i + ".";
            String id = properties.getProperty(prefix + "id", "");
            if (!id.isEmpty()) {
                String action = properties.getProperty(prefix + "action", "");
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
                String match = properties.getProperty(prefix + "match", "all").trim();
                int space = match.indexOf(' ');
                String matchType = match;
                if (space >= 0) {
                    matchType = match.substring(0, space);
                    match = match.substring(space + 1);
                }
                RequestMatcher matcher;
                switch (matchType) {
                    case "all":
                        matcher = new MatchAllRequestMatcher();
                        break;
                    case "regexp":
                        matcher = new URIRequestMatcher(match);
                        break;
                    default:
                        throw new ConfigurationNotValidException(prefix + "match can be only 'all' and 'regexp' at the moment");

                }
                LOG.log(Level.INFO, "configured route {0} action: {1} enabled: {2} matcher: {3}", new Object[]{id, action, enabled, matcher});
                RouteConfiguration config = new RouteConfiguration(id, action, enabled, matcher);
                addRoute(config);
            }
        }
    }

    private final class RandomBackendSelector implements BackendSelector {

        @Override
        public List<String> selectBackends(HttpRequest request, RoutingKey key) {
            ArrayList<String> result = new ArrayList<>(allbackendids);
            Collections.shuffle(result);
            return result;
        }

    }

    public StandardEndpointMapper() {
        this.backendSelector = new RandomBackendSelector();
    }

    public void addService(DirectorConfiguration service) throws ConfigurationNotValidException {
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
    public MapResult map(HttpRequest request, BackendHealthManager backendHealthManager) {
        for (RouteConfiguration route : routes) {
            if (!route.isEnabled()) {
                continue;
            }
            RoutingKey matchResult = route.matches(request);
            if (matchResult != null) {
                ActionConfiguration action = actions.get(route.getAction());
                if (action == null) {
                    LOG.info("no action '" + route.getAction() + "' -> not-found for " + request.uri() + ", valid " + actions.keySet());
                    return MapResult.NOT_FOUND;
                }

                if (ActionConfiguration.TYPE_STATIC.equals(action.getType())) {
                    return new MapResult(null, -1, MapResult.Action.STATIC)
                            .setResource(action.getFile())
                            .setErrorcode(action.getErrorcode());
                }

                List<String> selectedBackends = backendSelector.selectBackends(request, matchResult);
                LOG.log(Level.FINEST, "selected {0} backends for {1}", new Object[]{selectedBackends, request.uri()});
                for (String backendId : selectedBackends) {
                    switch (action.getType()) {
                        case ActionConfiguration.TYPE_PROXY: {
                            BackendConfiguration backend = this.backends.get(backendId);
                            if (backend != null && backendHealthManager.isAvailable(backend.getHost(), backend.getPort())) {
                                return new MapResult(backend.getHost(), backend.getPort(), MapResult.Action.PROXY);
                            }
                            break;
                        }
                        case ActionConfiguration.TYPE_CACHE:
                            BackendConfiguration backend = this.backends.get(backendId);
                            if (backend != null && backendHealthManager.isAvailable(backend.getHost(), backend.getPort())) {
                                return new MapResult(backend.getHost(), backend.getPort(), MapResult.Action.CACHE);
                            }
                            break;
                        default:
                            return MapResult.NOT_FOUND;
                    }
                }
            }
        }
        return MapResult.NOT_FOUND;
    }

}
