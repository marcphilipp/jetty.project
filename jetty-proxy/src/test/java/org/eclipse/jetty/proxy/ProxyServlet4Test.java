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
import java.io.InterruptedIOException;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
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
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeader;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyServlet4Test
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

    public static Stream<Arguments> transparentImpls()
    {
        return Stream.of(
            new ProxyServlet.Transparent()
            {
                @Override
                protected HttpClient newHttpClient()
                {
                    return newTrustAllClient(super.newHttpClient());
                }

                @Override
                public String toString()
                {
                    return Transparent.class.getName();
                }
            },
            new AsyncProxyServlet.Transparent()
            {
                @Override
                protected HttpClient newHttpClient()
                {
                    return newTrustAllClient(super.newHttpClient());
                }

                @Override
                public String toString()
                {
                    return Transparent.class.getName();
                }
            },
            new AsyncMiddleManServlet.Transparent()
            {
                @Override
                protected HttpClient newHttpClient()
                {
                    return newTrustAllClient(super.newHttpClient());
                }

                @Override
                public String toString()
                {
                    return Transparent.class.getName();
                }
            }
        ).map(Arguments::of);
    }

    private static HttpClient newTrustAllClient(HttpClient client)
    {
        SslContextFactory.Client sslContextFactory = client.getSslContextFactory();
        sslContextFactory.setTrustAll(true);
        return client;
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "/foo", "/proxy", "/test");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyEmptyContextWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "", "/proxy", "/test");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyEmptyTargetWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "/bar", "/proxy", "");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyEmptyContextEmptyTargetWithQuery(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithQuery(proxyServletClass, "", "/proxy", "");
    }

    private void testTransparentProxyWithQuery(AbstractProxyServlet proxyServletClass, String proxyToContext, String prefix, String target) throws Exception
    {
        final String query = "a=1&b=2";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                String expectedURI = proxyToContext + target;
                if (expectedURI.isEmpty())
                    expectedURI = "/";
                if (expectedURI.equals(req.getRequestURI()))
                {
                    if (query.equals(req.getQueryString()))
                    {
                        resp.setStatus(HttpStatus.OK_200);
                        return;
                    }
                }
                resp.setStatus(HttpStatus.NOT_FOUND_404);
            }
        });

        String proxyTo = "http://localhost:" + serverConnector.getLocalPort() + proxyToContext;
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyServletClass, params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(prefix + target + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithQueryWithSpaces(AbstractProxyServlet proxyServletClass) throws Exception
    {
        final String target = "/test";
        final String query = "a=1&b=2&c=1234%205678&d=hello+world";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                if (target.equals(req.getRequestURI()))
                {
                    if (query.equals(req.getQueryString()))
                    {
                        resp.setStatus(200);
                        return;
                    }
                }
                resp.setStatus(404);
            }
        });
        String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        String prefix = "/proxy";
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyServletClass, params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(prefix + target + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithoutPrefix(AbstractProxyServlet proxyServletClass) throws Exception
    {
        final String target = "/test";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.setStatus(target.equals(req.getRequestURI()) ? 200 : 404);
            }
        });
        final String proxyTo = "http://localhost:" + serverConnector.getLocalPort();
        Map<String, String> initParams = new HashMap<>();
        initParams.put("proxyTo", proxyTo);
        startProxy(proxyServletClass, initParams);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(target)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    /**
     * Only tests overridden ProxyServlet behavior, see CachingProxyServlet
     */
    @Test
    public void testCachingProxy() throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF};
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.getOutputStream().write(content);
            }
        });

        startProxy(CachingProxyServlet.class);
        startClient();

        // First request
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());

        // Second request should be cached
        response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertThat(response.getHeaders(), containsHeader(CachingProxyServlet.CACHE_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testRedirectsAreProxied(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                resp.sendRedirect("/");
            }
        });
        startProxy(proxyServletClass);
        startClient();

        client.setFollowRedirects(false);

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(302, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testGZIPContentIsProxied(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                resp.addHeader("Content-Encoding", "gzip");
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(resp.getOutputStream());
                gzipOutputStream.write(content);
                gzipOutputStream.close();
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testWrongContentLength(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {

        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                byte[] message = "tooshort".getBytes(StandardCharsets.US_ASCII);
                resp.setContentType("text/plain;charset=ascii");
                resp.setHeader("Content-Length", Long.toString(message.length + 1));
                resp.getOutputStream().write(message);
            }
        });
        startProxy(proxyServletClass);
        startClient();

        try
        {
            ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), greaterThanOrEqualTo(500));
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testCookiesFromDifferentClientsAreNotMixed(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final String name = "biscuit";
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");

                String value = req.getHeader(name);
                if (value != null)
                {
                    Cookie cookie = new Cookie(name, value);
                    cookie.setMaxAge(3600);
                    resp.addCookie(cookie);
                }
                else
                {
                    Cookie[] cookies = req.getCookies();
                    assertEquals(1, cookies.length);
                }
            }
        });
        startProxy(proxyServletClass);
        startClient();

        String value1 = "1";
        ContentResponse response1 = client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(name, value1))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response1.getStatus());
        assertTrue(response1.getHeaders().contains(PROXIED_HEADER));
        List<HttpCookie> cookies = client.getCookieStore().getCookies();
        assertEquals(1, cookies.size());
        assertEquals(name, cookies.get(0).getName());
        assertEquals(value1, cookies.get(0).getValue());

        HttpClient client2 = prepareClient(null);
        try
        {
            String value2 = "2";
            ContentResponse response2 = client2.newRequest("localhost", serverConnector.getLocalPort())
                .headers(headers -> headers.put(name, value2))
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(200, response2.getStatus());
            assertTrue(response2.getHeaders().contains(PROXIED_HEADER));
            cookies = client2.getCookieStore().getCookies();
            assertEquals(1, cookies.size());
            assertEquals(name, cookies.get(0).getName());
            assertEquals(value2, cookies.get(0).getValue());

            // Make a third request to be sure the proxy does not mix cookies
            ContentResponse response3 = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(200, response3.getStatus());
            assertTrue(response3.getHeaders().contains(PROXIED_HEADER));
        }
        finally
        {
            client2.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testResponseHeadersAreNotRemoved(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        proxyContext.stop();
        final String headerName = "X-Test";
        final String headerValue = "test-value";
        proxyContext.addFilter(new FilterHolder(new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig)
            {
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                ((HttpServletResponse)response).addHeader(headerName, headerValue);
                chain.doFilter(request, response);
            }

            @Override
            public void destroy()
            {
            }
        }), "/*", EnumSet.of(DispatcherType.REQUEST));
        proxyContext.start();
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(headerValue, response.getHeaders().get(headerName));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testHeadersListedByConnectionHeaderAreRemoved(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final Map<String, String> hopHeaders = new LinkedHashMap<>();
        hopHeaders.put(HttpHeader.TE.asString(), "gzip");
        hopHeaders.put(HttpHeader.CONNECTION.asString(), "Keep-Alive, Foo, Bar");
        hopHeaders.put("Foo", "abc");
        hopHeaders.put("Foo", "def");
        hopHeaders.put(HttpHeader.KEEP_ALIVE.asString(), "timeout=30");
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                List<String> names = Collections.list(request.getHeaderNames());
                for (String name : names)
                {
                    if (hopHeaders.containsKey(name))
                        throw new IOException("Hop header must not be proxied: " + name);
                }
            }
        });
        startProxy(proxyServletClass);
        startClient();

        Request request = client.newRequest("localhost", serverConnector.getLocalPort());
        hopHeaders.forEach((key, value) -> request.headers(headers -> headers.add(key, value)));
        ContentResponse response = request
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testExpect100ContinueRespond100Continue(Class<? extends ProxyServlet> proxyServletClass) throws Exception
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

                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();

                // Echo the content.
                IO.copy(input, response.getOutputStream());
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
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        {
                            if (Arrays.equals(content, getContent()))
                                clientLatch.countDown();
                        }
                    }
                }
            });

        // Wait until we arrive on the server.
        assertTrue(serverLatch1.await(5, TimeUnit.SECONDS));
        // The client should not send the content yet.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        // Make the server send the 100 Continue.
        serverLatch2.countDown();

        // The client has sent the content.
        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testExpect100ContinueRespond100ContinueDelayedRequestContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();
                // Echo the content.
                IO.copy(input, response.getOutputStream());
            }
        });
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        int chunk1 = content.length / 2;
        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.offer(ByteBuffer.wrap(content, 0, chunk1));
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
            .body(requestContent)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        {
                            if (Arrays.equals(content, getContent()))
                                clientLatch.countDown();
                        }
                    }
                }
            });

        // Wait a while and then offer more content.
        Thread.sleep(1000);
        requestContent.offer(ByteBuffer.wrap(content, chunk1, content.length - chunk1));
        requestContent.close();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
