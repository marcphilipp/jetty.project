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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyServlet3Test
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
    public void testExpect100ContinueRespond100ContinueSomeRequestContentThenFailure(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();
                try
                {
                    // Echo the content.
                    IO.copy(input, response.getOutputStream());
                }
                catch (IOException x)
                {
                    serverLatch.countDown();
                }
            }
        });
        startProxy(proxyServletClass);
        long idleTimeout = 1000;
        startClient(httpClient -> httpClient.setIdleTimeout(idleTimeout));

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
                .body(requestContent)
                .send(result ->
                {
                    if (result.isFailed())
                        clientLatch.countDown();
                });

        // Wait more than the idle timeout to break the connection.
        Thread.sleep(2 * idleTimeout);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyRequestFailureInTheMiddleOfProxyingSmallContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final CountDownLatch chunk1Latch = new CountDownLatch(1);
        final int chunk1 = 'q';
        final int chunk2 = 'w';
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                response.flushBuffer();

                // Wait for the client to receive this chunk.
                await(chunk1Latch, 5000);

                // Send second chunk, must not be received by proxy.
                output.write(chunk2);
            }

            private boolean await(CountDownLatch latch, long ms) throws IOException
            {
                try
                {
                    return latch.await(ms, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        final long proxyTimeout = 1000;
        Map<String, String> proxyParams = new HashMap<>();
        proxyParams.put("timeout", String.valueOf(proxyTimeout));
        startProxy(proxyServletClass, proxyParams);
        startClient();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        int port = serverConnector.getLocalPort();
        Request request = client.newRequest("localhost", port);
        request.send(listener);

        // Make the proxy request fail; given the small content, the
        // proxy-to-client response is not committed yet so it will be reset.
        TimeUnit.MILLISECONDS.sleep(2 * proxyTimeout);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(504, response.getStatus());

        // Make sure there is error page content, as the proxy-to-client response has been reset.
        InputStream input = listener.getInputStream();
        String body = IO.toString(input);
        assertThat(body, containsString("HTTP ERROR 504"));
        chunk1Latch.countDown();

        // Result succeeds because a 504 is a valid HTTP response.
        Result result = listener.await(5, TimeUnit.SECONDS);
        assertTrue(result.isSucceeded());

        // Make sure the proxy does not receive chunk2.
        assertEquals(-1, input.read());

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        ConnectionPool connectionPool = destination.getConnectionPool();
        assertTrue(connectionPool.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyRequestFailureInTheMiddleOfProxyingBigContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        int outputBufferSize = 1024;
        CountDownLatch chunk1Latch = new CountDownLatch(1);
        byte[] chunk1 = new byte[outputBufferSize];
        new Random().nextBytes(chunk1);
        int chunk2 = 'w';
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                response.flushBuffer();

                // Wait for the client to receive this chunk.
                await(chunk1Latch, 5000);

                // Send second chunk, must not be received by proxy.
                output.write(chunk2);
            }

            private boolean await(CountDownLatch latch, long ms) throws IOException
            {
                try
                {
                    return latch.await(ms, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        final long proxyTimeout = 1000;
        Map<String, String> proxyParams = new HashMap<>();
        proxyParams.put("timeout", String.valueOf(proxyTimeout));
        proxyParams.put("outputBufferSize", String.valueOf(outputBufferSize));
        startProxy(proxyServletClass, proxyParams);
        startClient();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        int port = serverConnector.getLocalPort();
        Request request = client.newRequest("localhost", port);
        request.send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        for (byte b : chunk1)
        {
            assertEquals(b & 0xFF, input.read());
        }

        TimeUnit.MILLISECONDS.sleep(2 * proxyTimeout);

        chunk1Latch.countDown();

        assertThrows(EOFException.class, () ->
        {
            // Make sure the proxy does not receive chunk2.
            input.read();
        });

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        ConnectionPool connectionPool = destination.getConnectionPool();
        assertTrue(connectionPool.isEmpty());
    }
}
