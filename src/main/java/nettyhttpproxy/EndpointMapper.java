package nettyhttpproxy;

import io.netty.handler.codec.http.HttpRequest;

/**
 * Maps requests to a remote HTTP server
 *
 * @author enrico.olivelli
 */
public abstract class EndpointMapper {

    public abstract MapResult map(HttpRequest request);
}
