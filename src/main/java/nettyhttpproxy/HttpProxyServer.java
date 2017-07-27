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
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import nettyhttpproxy.client.ConnectionsManager;
import nettyhttpproxy.client.impl.ConnectionsManagerImpl;

public class HttpProxyServer implements AutoCloseable {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final EndpointMapper mapper;
    private final ConnectionsManager connectionsManager;
    private final String host;
    private final int port;

    public HttpProxyServer(String host, int port, EndpointMapper mapper) {
        this.port = port;
        this.host = host;
        this.mapper = mapper;
        this.connectionsManager = new ConnectionsManagerImpl();
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
                    ch.pipeline().addLast(new ProxiedConnectionHandler(mapper, connectionsManager));

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
        if (connectionsManager != null) {
            connectionsManager.close();
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public ConnectionsManager getConnectionsManager() {
        return connectionsManager;
    }

}
