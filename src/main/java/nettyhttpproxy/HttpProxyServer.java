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
package nettyhttpproxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.util.ArrayList;
import java.util.List;
import nettyhttpproxy.client.ConnectionsManager;
import nettyhttpproxy.client.impl.ConnectionsManagerImpl;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;

public class HttpProxyServer implements AutoCloseable {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final EndpointMapper mapper;
    private final ConnectionsManager connectionsManager;

    private final List<NetworkListenerConfiguration> listeners = new ArrayList<>();
    private final List<Channel> listeningChannels = new ArrayList<>();

    public HttpProxyServer(EndpointMapper mapper) {
        this.mapper = mapper;
        this.connectionsManager = new ConnectionsManagerImpl();
    }

    public HttpProxyServer(String host, int port, EndpointMapper mapper) {
        this(mapper);
        listeners.add(new NetworkListenerConfiguration(host, port));
    }

    public void addListener(NetworkListenerConfiguration listener) {
        listeners.add(listener);
    }

    public void start() throws InterruptedException {
        try {
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
                        ch.pipeline().addLast(new ProxiedConnectionHandler(mapper, connectionsManager));

                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            for (NetworkListenerConfiguration listener : listeners) {
                listeningChannels.add(b.bind(listener.getHost(), listener.getPort()).sync().channel());
            }
        } catch (RuntimeException err) {
            close();
            throw err;
        }

    }

    @Override
    public void close() {
        for (Channel channel : listeningChannels) {
            channel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (connectionsManager != null) {
            connectionsManager.close();
        }
    }

    public ConnectionsManager getConnectionsManager() {
        return connectionsManager;
    }

}
