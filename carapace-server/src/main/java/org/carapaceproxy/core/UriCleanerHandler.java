package org.carapaceproxy.core;

import static reactor.netty.NettyPipeline.H2OrHttp11Codec;
import static reactor.netty.NettyPipeline.HttpTrafficHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UriCleanerHandler extends ChannelInboundHandlerAdapter {
    public static final UriCleanerHandler INSTANCE = new UriCleanerHandler();
    private final Logger logger = LoggerFactory.getLogger(UriCleanerHandler.class);

    private UriCleanerHandler() {
        super();
    }

    public void addToPipeline(final Channel channel) {
        if (channel.pipeline().get(HttpTrafficHandler) != null) {
            channel.pipeline().addBefore(HttpTrafficHandler, "uriEncoder", this);
        }
        if (channel.pipeline().get(H2OrHttp11Codec) != null) {
            channel.pipeline().addAfter(H2OrHttp11Codec, "uriEncoder", this);
        }
        logger.debug("Unsupported pipeline structure: {}; skipping...", channel.pipeline().toString());
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof final HttpRequest request) {
            request.setUri(request.uri()
                    .replaceAll("\\[", "%5B")
                    .replaceAll("]", "%5D")
            );
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public boolean isSharable() {
        return true;
    }
}
