package nettyhttpproxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;

public class HttpProxyServer implements AutoCloseable {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final EndpointMapper mapper;
    private final String host;
    private final int port;

    public HttpProxyServer(String host, int port, EndpointMapper mapper) {
        this.port = port;
        this.host = host;
        this.mapper = mapper;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(10);
        workerGroup = new NioEventLoopGroup(10);
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {

                    ch.pipeline().addLast(new HttpRequestDecoder());
                    ch.pipeline().addLast(new HttpResponseEncoder());
                    ch.pipeline().addLast(new ProxiedConnectionHandler(mapper));

                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        b.bind(host, port).sync();

    }

    @Override
    public void close() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

}
