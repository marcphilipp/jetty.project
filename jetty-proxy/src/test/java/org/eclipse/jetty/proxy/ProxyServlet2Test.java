//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyServlet2Test
{
    private static final String PROXIED_HEADER = "X-Proxied";

    public static Stream<Arguments> impls()
    {
        return Stream.of(
            ProxyServlet.class,
            AsyncProxyServlet.class,
            AsyncMiddleManServlet.class
        ).map(Arguments::of);
    }

    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServletContextHandler proxyContext;
    private AbstractProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;
    private ServerConnector tlsServerConnector;

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("server_keystore.p12").getAbsolutePath();
        sslContextFactory.setKeyStorePath(keyStorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        tlsServerConnector = new ServerConnector(server, new SslConnectionFactory(
            sslContextFactory,
            HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory());
        server.addConnector(tlsServerConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startProxy(proxyServletClass, new HashMap<>());
    }

    private void startProxy(Class<? extends ProxyServlet> proxyServletClass, Map<String, String> initParams) throws Exception
    {
        startProxy(proxyServletClass.getConstructor().newInstance(), initParams);
    }

    private void startProxy(AbstractProxyServlet proxyServlet, Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        String value = initParams.get("outputBufferSize");
        if (value != null)
            configuration.setOutputBufferSize(Integer.parseInt(value));
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        proxyContext = new ServletContextHandler(proxy, "/", true, false);
        this.proxyServlet = proxyServlet;
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        startClient(null);
    }

    private void startClient(Consumer<HttpClient> consumer) throws Exception
    {
        client = prepareClient(consumer);
    }

    private HttpClient prepareClient(Consumer<HttpClient> consumer) throws Exception
    {
        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");
        HttpClient result = new HttpClient();
        result.setExecutor(clientPool);
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        if (consumer != null)
            consumer.accept(result);
        result.start();
        return result;
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (proxy != null)
            proxy.stop();
        if (server != null)
            server.stop();
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithBigResponseContentWithSlowReader(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        // Create a 6 MiB file
        final int length = 6 * 1024;
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        final Path temp = Files.createTempFile(targetTestsDir, "test_", null);
        byte[] kb = new byte[1024];
        new Random().nextBytes(kb);
        try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE))
        {
            for (int i = 0; i < length; ++i)
            {
                output.write(kb);
            }
        }
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try (InputStream input = Files.newInputStream(temp))
                {
                    IO.copy(input, response.getOutputStream());
                }
            }
        });
        startProxy(proxyServletClass);
        startClient();

        Request request = client.newRequest("localhost", serverConnector.getLocalPort()).path("/proxy/test");
        final CountDownLatch latch = new CountDownLatch(1);
        request.send(new BufferingResponseListener(2 * length * 1024)
        {
            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                try
                {
                    // Slow down the reader
                    TimeUnit.MILLISECONDS.sleep(5);
                    super.onContent(response, content);
                }
                catch (InterruptedException x)
                {
                    response.abort(x);
                }
            }

            @Override
            public void onComplete(Result result)
            {
                assertFalse(result.isFailed());
                assertEquals(200, result.getResponse().getStatus());
                assertEquals(length * 1024, getContent().length);
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testExpect100ContinueRespond417ExpectationFailed(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch1 = new CountDownLatch(1);
        CountDownLatch serverLatch2 = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                serverLatch1.countDown();

                try
                {
                    serverLatch2.await(5, TimeUnit.SECONDS);
                }
                catch (Throwable x)
                {
                    throw new InterruptedIOException();
                }

                // Send the 417 Expectation Failed.
                response.setStatus(HttpStatus.EXPECTATION_FAILED_417);
            }
        });
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
                .body(new BytesRequestContent(content))
                .onRequestContent((request, buffer) -> contentLatch.countDown())
                .send(result ->
                {
                    if (result.isFailed())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.EXPECTATION_FAILED_417)
                            clientLatch.countDown();
                    }
                });

        // Wait until we arrive on the server.
        assertTrue(serverLatch1.await(5, TimeUnit.SECONDS));
        // The client should not send the content yet.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        // Make the server send the 417 Expectation Failed.
        serverLatch2.countDown();

        // The client should not send the content.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
