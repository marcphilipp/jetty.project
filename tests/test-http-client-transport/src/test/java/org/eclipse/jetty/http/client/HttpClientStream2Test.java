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
}
