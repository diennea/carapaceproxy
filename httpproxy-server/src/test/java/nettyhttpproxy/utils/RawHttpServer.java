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
package nettyhttpproxy.utils;

import javax.servlet.http.HttpServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * A Jetty based Http Server
 *
 * @author enrico.olivelli
 */
public class RawHttpServer implements AutoCloseable {

    private Server httpserver;
    private int port = 0; // start ephemeral

    private final HttpServlet servlet;

    public RawHttpServer(HttpServlet servlet) {
        this.servlet = servlet;
    }

    public int start() throws Exception {
        stop();

        httpserver = new Server(port);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        httpserver.setHandler(contexts);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder jerseyServlet = new ServletHolder(servlet);
        jerseyServlet.setInitOrder(0);
        context.addServlet(jerseyServlet, "/");
        contexts.addHandler(context);
        httpserver.start();

        this.port = ((ServerConnector) httpserver.getConnectors()[0]).getLocalPort(); // keep same port on restart
        return port;
    }

    public void restart() throws Exception {
        stop();
        start();
    }

    public void stop() throws Exception {
        if (httpserver != null) {
            httpserver.stop();
        }
        httpserver = null;
    }

    @Override
    public void close() throws Exception {
        stop();
    }
}
