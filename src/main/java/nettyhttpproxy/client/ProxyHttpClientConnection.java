package nettyhttpproxy.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.ProxiedConnectionHandler;

public class ProxyHttpClientConnection implements AutoCloseable {

    private final String host;
    private final int port;
    private EventLoopGroup group;
    private Channel channel;

    public ProxyHttpClientConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void sendRequest(HttpRequest request, ProxiedConnectionHandler handler, Channel peerChannel) {
        group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new HttpClientCodec());
                    ch.pipeline().addLast(new Handler(handler, peerChannel));
                }
            });

        channel = b.connect(host, port)
            .addListener(new GenericFutureListener<Future<Void>>() {
                @Override
                public void operationComplete(Future<Void> future) throws Exception {
                    channel.writeAndFlush(request);
                }

            }).channel();
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close(); // no wait for synch
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private static final Logger LOG = Logger.getLogger(ProxyHttpClientConnection.class.getName());

    public void sendHttpObject(Object msg) {
        channel.writeAndFlush(msg);
    }

    private class Handler extends SimpleChannelInboundHandler<HttpObject> {

        private final ProxiedConnectionHandler serverHandler;
        private final Channel peerChannel;

        public Handler(ProxiedConnectionHandler handler, Channel peerChannel) {
            this.serverHandler = handler;
            this.peerChannel = peerChannel;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpContent) {
                HttpContent f = (HttpContent) msg;
                LOG.log(Level.FINE, "proxying {0}: {1}", new Object[]{msg.getClass(), msg});
                serverHandler.receivedFromRemote(f.copy(), peerChannel);
            } else if (msg instanceof DefaultHttpResponse) {
                DefaultHttpResponse f = (DefaultHttpResponse) msg;
                LOG.log(Level.FINE, "proxying {0}: {1}", new Object[]{msg.getClass(), msg});
                serverHandler.receivedFromRemote(new DefaultHttpResponse(f.protocolVersion(),
                    f.status(), f.headers()), peerChannel);
            } else {
                LOG.log(Level.SEVERE, "unknown mesasge type " + msg.getClass(), new Exception("unknown mesasge type " + msg.getClass())
                    .fillInStackTrace());
            }

        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            serverHandler.readCompletedFromRemote(peerChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

}
