package nettyhttpproxy.impl;

import io.netty.handler.codec.http.HttpRequest;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.MapResult;

public class SimpleEndpointMapper extends EndpointMapper {

    private final String host;
    private final int port;

    public SimpleEndpointMapper(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public MapResult map(HttpRequest request) {
        String uri = request.uri();
        if (uri.contains("not-found")) {
            return MapResult.NOT_FOUND;
        } else if (uri.contains("redir")) {
            return new MapResult(host, port,
                MapResult.Action.PIPE);
        } else {
            return new MapResult(null, 0, MapResult.Action.DEBUG);
        }
    }
}
