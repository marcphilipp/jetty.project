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
import java.io.PrintWriter;
import java.net.ConnectException;
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
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
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
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeader;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyServlet1Test
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
    public void testProxyDown(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        startClient();
        // Shutdown the proxy
        proxy.stop();

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertThat(x.getCause(), instanceOf(ConnectException.class));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithoutContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals("OK", response.getReason());
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithResponseContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[1024];
        new Random().nextBytes(content);
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
        startProxy(proxyServletClass);
        startClient();

        ContentResponse[] responses = new ContentResponse[10];
        for (int i = 0; i < 10; ++i)
        {
            // Request is for the target server
            responses[i] = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        }

        for (int i = 0; i < 10; ++i)
        {
            assertEquals(200, responses[i].getStatus());
            assertTrue(responses[i].getHeaders().contains(PROXIED_HEADER));
            assertArrayEquals(content, responses[i].getContent());
        }
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithRequestContentAndResponseContent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                IO.copy(req.getInputStream(), resp.getOutputStream());
            }
        });
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithBigRequestContentIgnored(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                try
                {
                    // Give some time to the proxy to
                    // upload the content to the server.
                    Thread.sleep(1000);

                    if (req.getHeader("Via") != null)
                        resp.addHeader(PROXIED_HEADER, "true");
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        startProxy(proxyServletClass);
        startClient();

        byte[] content = new byte[128 * 1024];
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithBigRequestContentConsumed(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final byte[] content = new byte[128 * 1024];
        new Random().nextBytes(content);
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
                InputStream input = req.getInputStream();
                int index = 0;

                byte[] buffer = new byte[16 * 1024];
                while (true)
                {
                    int value = input.read(buffer);
                    if (value < 0)
                        break;
                    for (int i = 0; i < value; i++)
                    {
                        assertEquals(content[index] & 0xFF, buffer[i] & 0xFF, "Content mismatch at index=" + index);
                        ++index;
                    }
                }
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWithQueryString(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getOutputStream().print(req.getQueryString());
            }
        });
        startProxy(proxyServletClass);
        startClient();

        String query = "a=1&b=%E2%82%AC";
        ContentResponse response = client.newRequest("http://localhost:" + serverConnector.getLocalPort() + "/?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertEquals(query, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyLongPoll(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        final long timeout = 1000;
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            {
                if (!request.isAsyncStarted())
                {
                    final AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(timeout);
                    asyncContext.addListener(new AsyncListener()
                    {
                        @Override
                        public void onComplete(AsyncEvent event)
                        {
                        }

                        @Override
                        public void onTimeout(AsyncEvent event)
                        {
                            if (request.getHeader("Via") != null)
                                response.addHeader(PROXIED_HEADER, "true");
                            asyncContext.complete();
                        }

                        @Override
                        public void onError(AsyncEvent event)
                        {
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event)
                        {
                        }
                    });
                }
            }
        });
        startProxy(proxyServletClass);
        startClient();

        Response response = client.newRequest("localhost", serverConnector.getLocalPort())
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyXForwardedHostHeaderIsPresent(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                PrintWriter writer = resp.getWriter();
                writer.write(req.getHeader("X-Forwarded-Host"));
                writer.flush();
            }
        });
        startProxy(proxyServletClass);
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        assertThat("Response expected to contain content of X-Forwarded-Host Header from the request",
            response.getContentAsString(),
            equalTo("localhost:" + serverConnector.getLocalPort()));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyViaHeaderIsAdded(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                PrintWriter writer = response.getWriter();
                List<String> viaValues = Collections.list(request.getHeaders("Via"));
                writer.write(String.join(", ", viaValues));
            }
        });
        String viaHost = "my-good-via-host.example.org";
        startProxy(proxyServletClass, Collections.singletonMap("viaHost", viaHost));
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());
        assertThat(response.getContentAsString(), equalTo("1.1 " + viaHost));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyViaHeaderValueIsAppended(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Make sure the proxy coalesced the Via headers into just one.
                org.eclipse.jetty.server.Request jettyRequest = (org.eclipse.jetty.server.Request)request;
                assertEquals(1, jettyRequest.getHttpFields().getFields(HttpHeader.VIA).size());
                PrintWriter writer = response.getWriter();
                List<String> viaValues = Collections.list(request.getHeaders("Via"));
                writer.write(String.join(", ", viaValues));
            }
        });
        String viaHost = "beatrix";
        startProxy(proxyServletClass, Collections.singletonMap("viaHost", viaHost));
        startClient();

        String existingViaHeader = "1.0 charon";
        ContentResponse response = client.newRequest("http://localhost:" + serverConnector.getLocalPort())
            .header(HttpHeader.VIA, existingViaHeader)
            .send();
        String expected = String.join(", ", existingViaHeader, "1.1 " + viaHost);
        assertThat(response.getContentAsString(), equalTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/2.0", "FCGI/1.0"})
    public void testViaHeaderProtocols(String protocol) throws Exception
    {
        startServer(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                PrintWriter writer = response.getWriter();
                List<String> viaValues = Collections.list(request.getHeaders("Via"));
                writer.write(String.join(", ", viaValues));
            }
        });
        String viaHost = "proxy";
        startProxy(new ProxyServlet()
        {
            @Override
            protected void addViaHeader(HttpServletRequest clientRequest, Request proxyRequest)
            {
                HttpServletRequest wrapped = new HttpServletRequestWrapper(clientRequest)
                {
                    @Override
                    public String getProtocol()
                    {
                        return protocol;
                    }
                };
                super.addViaHeader(wrapped, proxyRequest);
            }
        }, Collections.singletonMap("viaHost", viaHost));
        startClient();

        ContentResponse response = client.GET("http://localhost:" + serverConnector.getLocalPort());

        String expectedProtocol = protocol.startsWith("HTTP/") ? protocol.substring("HTTP/".length()) : protocol;
        String expected = expectedProtocol + " " + viaHost;
        assertThat(response.getContentAsString(), equalTo(expected));
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyWhiteList(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        startClient();
        int port = serverConnector.getLocalPort();
        proxyServlet.getWhiteListHosts().add("127.0.0.1:" + port);

        // Try with the wrong host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(403, response.getStatus());

        // Try again with the right host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testProxyBlackList(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new EmptyHttpServlet());
        startProxy(proxyServletClass);
        startClient();
        int port = serverConnector.getLocalPort();
        proxyServlet.getBlackListHosts().add("localhost:" + port);

        // Try with the wrong host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(403, response.getStatus());

        // Try again with the right host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("impls")
    public void testClientExcludedHosts(Class<? extends ProxyServlet> proxyServletClass) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER, "true");
            }
        });
        startProxy(proxyServletClass);
        startClient();
        int port = serverConnector.getLocalPort();
        client.getProxyConfiguration().getProxies().get(0).getExcludedAddresses().add("127.0.0.1:" + port);

        // Try with a proxied host
        ContentResponse response = client.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));

        // Try again with an excluded host
        response = client.newRequest("127.0.0.1", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertFalse(response.getHeaders().contains(PROXIED_HEADER));
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
                    return ProxyServlet.Transparent.class.getName();
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
                    return AsyncProxyServlet.Transparent.class.getName();
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
                    return AsyncMiddleManServlet.Transparent.class.getName();
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
    public void testTransparentProxy(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyServletClass, "http", "/proxy");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyTls(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyServletClass, "https", "/proxy");
    }

    @ParameterizedTest
    @MethodSource("transparentImpls")
    public void testTransparentProxyWithRootContext(AbstractProxyServlet proxyServletClass) throws Exception
    {
        testTransparentProxyWithPrefix(proxyServletClass, "http", "/");
    }

    private void testTransparentProxyWithPrefix(AbstractProxyServlet proxyServletClass, String scheme, String prefix) throws Exception
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
        int serverPort = serverConnector.getLocalPort();
        if (HttpScheme.HTTPS.is(scheme))
            serverPort = tlsServerConnector.getLocalPort();
        String proxyTo = scheme + "://localhost:" + serverPort;
        Map<String, String> params = new HashMap<>();
        params.put("proxyTo", proxyTo);
        params.put("prefix", prefix);
        startProxy(proxyServletClass, params);
        startClient();

        // Make the request to the proxy, it should transparently forward to the server
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(StringUtil.replace((prefix + target), "//", "/"))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(PROXIED_HEADER));
    }
}
