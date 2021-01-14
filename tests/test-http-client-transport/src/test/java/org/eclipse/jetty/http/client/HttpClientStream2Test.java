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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientStream2Test extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testUploadWithDeferredContentProviderFromInputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        try (AsyncRequestContent content = new AsyncRequestContent())
        {
            scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .body(content)
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                        latch.countDown();
                });

            // Make sure we provide the content *after* the request has been "sent".
            Thread.sleep(1000);

            try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[1024]))
            {
                byte[] buffer = new byte[200];
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    content.offer(ByteBuffer.wrap(buffer, 0, read));
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentAvailableCallbacksNotifiedOnce(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger succeeds = new AtomicInteger();
        try (AsyncRequestContent content = new AsyncRequestContent())
        {
            // Make the content immediately available.
            content.offer(ByteBuffer.allocate(1024), new Callback()
            {
                @Override
                public void succeeded()
                {
                    succeeds.incrementAndGet();
                }
            });

            scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .body(content)
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                        latch.countDown();
                });
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, succeeds.get());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentProviderRacingWithSend(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        byte[] data = new byte[512];
        AsyncRequestContent content = new AsyncRequestContent()
        {
            @Override
            public void demand()
            {
                super.demand();
                // Simulate a concurrent call
                offer(ByteBuffer.wrap(data));
                close();
            }
        };

        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded() &&
                        result.getResponse().getStatus() == 200 &&
                        Arrays.equals(data, getContent()))
                        latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithOutputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] data = new byte[512];
        CountDownLatch latch = new CountDownLatch(1);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded() &&
                        result.getResponse().getStatus() == 200 &&
                        Arrays.equals(data, getContent()))
                        latch.countDown();
                }
            });

        // Make sure we provide the content *after* the request has been "sent".
        Thread.sleep(1000);

        try (OutputStream output = content.getOutputStream())
        {
            output.write(data);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBigUploadWithOutputStreamFromInputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] data = new byte[16 * 1024 * 1024];
        new Random().nextBytes(data);
        CountDownLatch latch = new CountDownLatch(1);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .body(content)
            .send(new BufferingResponseListener(data.length)
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertEquals(200, result.getResponse().getStatus());
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        // Make sure we provide the content *after* the request has been "sent".
        Thread.sleep(1000);

        try (OutputStream output = content.getOutputStream())
        {
            IO.copy(new ByteArrayInputStream(data), output);
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithOutputStreamFailureToConnect(Transport transport) throws Exception
    {
        init(transport);

        long connectTimeout = 1000;
        scenario.start(new EmptyServerHandler(), httpClient -> httpClient.setConnectTimeout(connectTimeout));

        byte[] data = new byte[512];
        CountDownLatch latch = new CountDownLatch(1);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        String uri = "http://0.0.0.1";
        if (scenario.getNetworkConnectorLocalPort().isPresent())
            uri += ":" + scenario.getNetworkConnectorLocalPort().get();
        scenario.client.newRequest(uri)
            .scheme(scenario.getScheme())
            .body(content)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertThrows(IOException.class, () ->
        {
            try (OutputStream output = content.getOutputStream())
            {
                output.write(data);
            }
        });

        assertTrue(latch.await(2 * connectTimeout, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentProviderFailsMultipleOffers(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());

        CountDownLatch failLatch = new CountDownLatch(2);
        Callback callback = new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                failLatch.countDown();
            }
        };

        CountDownLatch completeLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .body(content)
            .onRequestBegin(request ->
            {
                content.offer(ByteBuffer.wrap(new byte[256]), callback);
                content.offer(ByteBuffer.wrap(new byte[256]), callback);
                request.abort(new Exception("explicitly_thrown_by_test"));
            })
            .send(result ->
            {
                if (result.isFailed())
                    completeLatch.countDown();
            });
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failLatch.await(5, TimeUnit.SECONDS));

        // Make sure that adding more content results in the callback to be failed.
        CountDownLatch latch = new CountDownLatch(1);
        content.offer(ByteBuffer.wrap(new byte[128]), new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithConnectFailureClosesStream(Transport transport) throws Exception
    {
        init(transport);

        long connectTimeout = 1000;
        scenario.start(new EmptyServerHandler(), httpClient -> httpClient.setConnectTimeout(connectTimeout));

        CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8))
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };
        InputStreamRequestContent content = new InputStreamRequestContent(stream);

        CountDownLatch completeLatch = new CountDownLatch(1);
        String uri = "http://0.0.0.1";
        if (scenario.getNetworkConnectorLocalPort().isPresent())
            uri += ":" + scenario.getNetworkConnectorLocalPort().get();
        scenario.client.newRequest(uri)
            .scheme(scenario.getScheme())
            .body(content)
            .send(result ->
            {
                assertTrue(result.isFailed());
                completeLatch.countDown();
            });

        assertTrue(completeLatch.await(2 * connectTimeout, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithConcurrentServerCloseClosesStream(Transport transport) throws Exception
    {
        init(transport);
        CountDownLatch serverLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                serverLatch.countDown();
            }
        });

        AtomicBoolean commit = new AtomicBoolean();
        CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                // This method will be called few times before
                // the request is committed.
                // We wait for the request to commit, and we
                // wait for the request to reach the server,
                // to be sure that the server endPoint has
                // been created, before stopping the connector.

                if (commit.get())
                {
                    try
                    {
                        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
                        scenario.connector.stop();
                        return 0;
                    }
                    catch (Throwable x)
                    {
                        throw new IOException(x);
                    }
                }
                else
                {
                    return scenario.connector.isStopped() ? -1 : 0;
                }
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };
        InputStreamRequestContent content = new InputStreamRequestContent(stream, 1);

        CountDownLatch completeLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .body(content)
            .onRequestCommit(request -> commit.set(true))
            .send(result ->
            {
                assertTrue(result.isFailed());
                completeLatch.countDown();
            });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerBufferedRead(Transport transport) throws Exception
    {
        init(transport);
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                asyncContextRef.set(request.startAsync());
                latch.countDown();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        AsyncContext asyncContext = asyncContextRef.get();
        assertNotNull(asyncContext);

        Random random = new Random();

        byte[] chunk = new byte[64];
        random.nextBytes(chunk);
        ServletOutputStream output = asyncContext.getResponse().getOutputStream();
        output.write(chunk);
        output.flush();

        // Use a buffer larger than the data
        // written to test that the read returns.
        byte[] buffer = new byte[2 * chunk.length];
        InputStream stream = listener.getInputStream();
        int totalRead = 0;
        while (totalRead < chunk.length)
        {
            int read = stream.read(buffer);
            assertTrue(read > 0);
            totalRead += read;
        }

        asyncContext.complete();

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerWithRedirect(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                if (target.startsWith("/303"))
                    response.sendRedirect("/200");
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .path("/303")
            .followRedirects(true)
            .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertTrue(result.isSucceeded());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testClientDefersContentServerIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        CountDownLatch dataLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        dataLatch.countDown();
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        dataLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        errorLatch.countDown();
                        response.setStatus(HttpStatus.REQUEST_TIMEOUT_408);
                        asyncContext.complete();
                    }
                });
            }
        });
        long idleTimeout = 1000;
        scenario.setServerIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = "[{\"key\":\"value\"}]".getBytes(StandardCharsets.UTF_8);
        OutputStreamRequestContent content = new OutputStreamRequestContent("application/json;charset=UTF-8")
        {
            @Override
            public long getLength()
            {
                return bytes.length;
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .body(content)
            .onResponseSuccess(response ->
            {
                assertEquals(HttpStatus.REQUEST_TIMEOUT_408, response.getStatus());
                latch.countDown();
            })
            .send(null);

        // Wait for the server to idle timeout.
        Thread.sleep(2 * idleTimeout);

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));

        // Do not send the content to the server.

        assertFalse(dataLatch.await(1, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
