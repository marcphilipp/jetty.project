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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClient2Test extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithInputStreamResponseListener(Transport transport) throws Exception
    {
        init(transport);
        String content = "hello world";
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().print(content);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .onResponseSuccess(response -> latch.countDown())
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        // Response cannot succeed until we read the content.
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS));

        InputStream input = listener.getInputStream();
        assertEquals(content, IO.toString(input));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConnectionListener(Transport transport) throws Exception
    {
        init(transport);
        scenario.startServer(new EmptyServerHandler());
        long idleTimeout = 1000;
        scenario.startClient(httpClient -> httpClient.setIdleTimeout(idleTimeout));

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        scenario.client.addBean(new org.eclipse.jetty.io.Connection.Listener()
        {
            @Override
            public void onOpened(org.eclipse.jetty.io.Connection connection)
            {
                openLatch.countDown();
            }

            @Override
            public void onClosed(org.eclipse.jetty.io.Connection connection)
            {
                closeLatch.countDown();
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(openLatch.await(1, TimeUnit.SECONDS));

        Thread.sleep(2 * idleTimeout);
        assertTrue(closeLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncResponseContentBackPressure(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // Large write to generate multiple DATA frames.
                response.getOutputStream().write(new byte[256 * 1024]);
            }
        });

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
        scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .onResponseContentAsync((response, content, callback) ->
                {
                    if (counter.incrementAndGet() == 1)
                    {
                        callbackRef.set(callback);
                        latchRef.get().countDown();
                    }
                    else
                    {
                        callback.succeeded();
                    }
                })
                .send(result -> completeLatch.countDown());

        assertTrue(latchRef.get().await(5, TimeUnit.SECONDS));
        // Wait some time to verify that back pressure is applied correctly.
        Thread.sleep(1000);
        assertEquals(1, counter.get());
        callbackRef.get().succeeded();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testResponseWithContentCompleteListenerInvokedOnce(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getWriter().write("Jetty");
            }
        });

        AtomicInteger completes = new AtomicInteger();
        scenario.client.newRequest(scenario.newURI())
                .send(result -> completes.incrementAndGet());

        sleep(1000);

        assertEquals(1, completes.get());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testHEADResponds200(Transport transport) throws Exception
    {
        init(transport);
        testHEAD(scenario.servletPath, HttpStatus.OK_200);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testHEADResponds404(Transport transport) throws Exception
    {
        init(transport);
        testHEAD("/notMapped", HttpStatus.NOT_FOUND_404);
    }

    private void testHEAD(String path, int status) throws Exception
    {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().write(data);
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
                .method(HttpMethod.HEAD)
                .path(path)
                .send();

        assertEquals(status, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testHEADWithAcceptHeaderAndSendError(Transport transport) throws Exception
    {
        init(transport);
        int status = HttpStatus.BAD_REQUEST_400;
        scenario.start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.sendError(status);
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
                .method(HttpMethod.HEAD)
                .path(scenario.servletPath)
                .headers(headers -> headers.put(HttpHeader.ACCEPT, "*/*"))
                .send();

        assertEquals(status, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testHEADWithContentLengthGreaterThanMaxBufferingCapacity(Transport transport) throws Exception
    {
        int length = 1024;
        init(transport);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentLength(length);
                response.getOutputStream().write(new byte[length]);
            }
        });

        org.eclipse.jetty.client.api.Request request = scenario.client
                .newRequest(scenario.newURI())
                .method(HttpMethod.HEAD)
                .path(scenario.servletPath);
        FutureResponseListener listener = new FutureResponseListener(request, length / 2);
        request.send(listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testOneDestinationPerUser(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());

        int runs = 4;
        int users = 16;
        for (int i = 0; i < runs; ++i)
        {
            for (int j = 0; j < users; ++j)
            {
                ContentResponse response = scenario.client.newRequest(scenario.newURI())
                        .tag(j)
                        .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }

        List<Destination> destinations = scenario.client.getDestinations();
        assertEquals(users, destinations.size());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIPv6Host(Transport transport) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());

        init(transport);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType("text/plain");
                response.getOutputStream().print(request.getHeader("Host"));
            }
        });

        // Test with a full URI.
        String hostAddress = "::1";
        String host = "[" + hostAddress + "]";
        int port = Integer.parseInt(scenario.getNetworkConnectorLocalPort().get());
        String uri = scenario.getScheme() + "://" + host + ":" + port + "/path";
        ContentResponse response = scenario.client.newRequest(uri)
                .method(HttpMethod.PUT)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));

        // Test with host address.
        response = scenario.client.newRequest(hostAddress, port)
                .scheme(scenario.getScheme())
                .method(HttpMethod.PUT)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));

        // Test with host.
        response = scenario.client.newRequest(host, port)
                .scheme(scenario.getScheme())
                .method(HttpMethod.PUT)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));

        assertEquals(1, scenario.client.getDestinations().size());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRequestWithDifferentDestination(Transport transport) throws Exception
    {
        init(transport);

        String requestScheme = HttpScheme.HTTPS.is(scenario.getScheme()) ? "http" : "https";
        String requestHost = "otherHost.com";
        int requestPort = 8888;
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                HttpURI uri = jettyRequest.getHttpURI();
                assertEquals(requestHost, uri.getHost());
                assertEquals(requestPort, uri.getPort());
                if (scenario.transport == Transport.H2C || scenario.transport == Transport.H2)
                    assertEquals(requestScheme, jettyRequest.getMetaData().getURI().getScheme());
            }
        });
        if (transport.isTlsBased())
            scenario.httpConfig.getCustomizer(SecureRequestCustomizer.class).setSniHostCheck(false);

        Origin origin = new Origin(scenario.getScheme(), "localhost", scenario.getNetworkConnectorLocalPortInt().get());
        HttpDestination destination = scenario.client.resolveDestination(origin);

        org.eclipse.jetty.client.api.Request request = scenario.client.newRequest(requestHost, requestPort)
                .scheme(requestScheme)
                .path("/path");

        CountDownLatch resultLatch = new CountDownLatch(1);
        destination.send(request, result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            resultLatch.countDown();
        });

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    private void sleep(long time) throws IOException
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }
}
