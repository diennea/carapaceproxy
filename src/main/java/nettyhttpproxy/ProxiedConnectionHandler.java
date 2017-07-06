package nettyhttpproxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import io.netty.handler.codec.http.HttpUtil;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.client.ProxyHttpClientConnection;

public class ProxiedConnectionHandler extends SimpleChannelInboundHandler<Object> {

    private final EndpointMapper mapper;
    private HttpRequest request;
    private MapResult action;
    private ProxyHttpClientConnection pipe;
    private final StringBuilder output = new StringBuilder();

    public ProxiedConnectionHandler(EndpointMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest request = this.request = (HttpRequest) msg;

            action = mapper.map(request);
            LOG.log(Level.INFO, "Mapped " + request.uri() + " to " + action);
            switch (action.action) {
                case NOTFOUND:
                    if (HttpUtil.is100ContinueExpected(request)) {
                        send100Continue(ctx);
                    }
                    return;
                case DEBUG:
                    if (HttpUtil.is100ContinueExpected(request)) {
                        send100Continue(ctx);
                    }
                    startDebugMessage(request);
                    return;
                case PIPE:
                case CACHE:
                    pipe = new ProxyHttpClientConnection(action.host, action.port);
                    pipe.sendRequest(request, this, ctx.channel());
                    return;
                default:
                    throw new IllegalStateException("not yet implemented");
            }

        } else if (msg instanceof LastHttpContent) {
            LastHttpContent trailer = (LastHttpContent) msg;
            HttpContent httpContent = (HttpContent) msg;
            switch (action.action) {
                case DEBUG: {
                    continueDebugMessage(httpContent, msg);
                    FullHttpResponse response = new DefaultFullHttpResponse(
                        HTTP_1_1, trailer.decoderResult().isSuccess() ? OK : BAD_REQUEST,
                        Unpooled.copiedBuffer(output.toString(), CharsetUtil.UTF_8));
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                    if (!writeResponse(response, ctx)) {
                        // If keep-alive is off, close the connection once the content is fully written.
                        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                    break;
                }
                case NOTFOUND: {
                    FullHttpResponse response = new DefaultFullHttpResponse(
                        HTTP_1_1, NOT_FOUND);
                    if (!writeResponse(response, ctx)) {
                        // If keep-alive is off, close the connection once the content is fully written.
                        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                    break;
                }
                case CACHE:
                case PIPE: {
                    pipe.sendHttpObject(msg);
                    break;
                }

                default:
                    throw new IllegalStateException("not yet implemented");
            }
        } else if (msg instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) msg;
            switch (action.action) {
                case DEBUG:
                    continueDebugMessage(httpContent, msg);
                    break;
            }
        }

    }
    private static final Logger LOG = Logger.getLogger(ProxiedConnectionHandler.class.getName());

    private void continueDebugMessage(HttpContent httpContent, Object msg) {
        ByteBuf content = httpContent.content();
        if (content.isReadable()) {
            output.append("CONTENT: ");
            output.append(content.toString(CharsetUtil.UTF_8));
            output.append("\r\n");
            appendDecoderResult(output, request);
        }
        if (msg instanceof LastHttpContent) {
            output.append("END OF CONTENT\r\n");
            LastHttpContent trailer = (LastHttpContent) msg;
            if (!trailer.trailingHeaders().isEmpty()) {
                output.append("\r\n");
                for (CharSequence name : trailer.trailingHeaders().names()) {
                    for (CharSequence value : trailer.trailingHeaders().getAll(name)) {
                        output.append("TRAILING HEADER: ");
                        output.append(name).append(" = ").append(value).append("\r\n");
                    }
                }
                output.append("\r\n");
            }
        }
    }

    private void startDebugMessage(HttpRequest request1) {
        output.setLength(0);
        output.append("WELCOME TO THE WILD WILD WEB SERVER\r\n");
        output.append("===================================\r\n");
        output.append("VERSION: ").append(request1.protocolVersion()).append("\r\n");
        output.append("HOSTNAME: ").append(request1.headers().get(HttpHeaderNames.HOST, "unknown")).append("\r\n");
        output.append("REQUEST_URI: ").append(request1.uri()).append("\r\n\r\n");
        HttpHeaders headers = request1.headers();
        if (!headers.isEmpty()) {
            for (Map.Entry<String, String> h : headers) {
                CharSequence key = h.getKey();
                CharSequence value = h.getValue();
                output.append("HEADER: ").append(key).append(" = ").append(value).append("\r\n");
            }
            output.append("\r\n");
        }
        appendDecoderResult(output, request1);
    }

    private void appendDecoderResult(StringBuilder buf, HttpObject o) {
        DecoderResult result = o.decoderResult();
        if (result.isSuccess()) {
            return;
        }

        buf.append(".. WITH DECODER FAILURE: ");
        buf.append(result.cause());
        buf.append("\r\n");
    }

    private boolean writeResponse(FullHttpResponse response, ChannelHandlerContext ctx) {
        // Decide whether to close the connection or not.
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        // Build the response object.

        if (keepAlive) {
            // Add 'Content-Length' header only for a keep-alive connection.
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        // Write the response.
        ctx.write(response);

        return keepAlive;
    }

    private void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, CONTINUE);
        ctx.write(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    public void receivedFromRemote(HttpObject msg, Channel channel) {
        LOG.log(Level.INFO, "received from remote server:" + msg);
        channel.writeAndFlush(msg);
        if (msg instanceof LastHttpContent) {
            if (pipe != null) {
                LOG.log(Level.INFO, "closing pipe " + pipe);
                pipe.close();
                pipe = null;
            }
        }
    }

    public void readCompletedFromRemote(Channel channel) {
        LOG.log(Level.INFO, "readCompletedFromRemote");
        channel.flush();
    }
}
