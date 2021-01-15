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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.zip.GZIPOutputStream;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientDemand2Test extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTwoListenersWithDifferentDemand(Transport transport) throws Exception
    {
        init(transport);

        int bufferSize = 512;
        byte[] content = new byte[10 * bufferSize];
        new Random().nextBytes(content);
        scenario.startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentLength(content.length);
                response.getOutputStream().write(content);
            }
        });
        scenario.startClient(client ->
        {
            client.setByteBufferPool(new MappedByteBufferPool(bufferSize));
            client.setResponseBufferSize(bufferSize);
        });

        AtomicInteger chunks = new AtomicInteger();
        Response.DemandedContentListener listener1 = (response, demand, content1, callback) ->
        {
            callback.succeeded();
            // The first time, demand infinitely.
            if (chunks.incrementAndGet() == 1)
                demand.accept(Long.MAX_VALUE);
        };
        BlockingQueue<ByteBuffer> contentQueue = new LinkedBlockingQueue<>();
        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> demandLatch = new AtomicReference<>(new CountDownLatch(1));
        Response.DemandedContentListener listener2 = (response, demand, content12, callback) ->
        {
            contentQueue.offer(content12);
            demandRef.set(demand);
            demandLatch.get().countDown();
        };

        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentDemanded(listener1)
            .onResponseContentDemanded(listener2)
            .send(result ->
            {
                assertFalse(result.isFailed(), String.valueOf(result.getFailure()));
                Response response = result.getResponse();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                resultLatch.countDown();
            });

        assertTrue(demandLatch.get().await(5, TimeUnit.SECONDS));
        LongConsumer demand = demandRef.get();
        assertNotNull(demand);
        assertEquals(1, contentQueue.size());
        assertNotNull(contentQueue.poll());

        // Must not get additional content because listener2 did not demand.
        assertNull(contentQueue.poll(1, TimeUnit.SECONDS));

        // Now demand, we should get content in both listeners.
        demandLatch.set(new CountDownLatch(1));
        demand.accept(1);

        assertNotNull(contentQueue.poll(5, TimeUnit.SECONDS));
        assertEquals(2, chunks.get());

        // Demand the rest and verify the result.
        assertTrue(demandLatch.get().await(5, TimeUnit.SECONDS));
        demand = demandRef.get();
        assertNotNull(demand);
        demand.accept(Long.MAX_VALUE);
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testGZippedResponseContentWithAsyncDemand(Transport transport) throws Exception
    {
        init(transport);

        int chunks = 64;
        byte[] content = new byte[chunks * 1024];
        new Random().nextBytes(content);

        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try (GZIPOutputStream gzip = new GZIPOutputStream(response.getOutputStream()))
                {
                    response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                    for (int i = 0; i < chunks; ++i)
                    {
                        Thread.sleep(10);
                        gzip.write(content, i * 1024, 1024);
                    }
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        byte[] bytes = new byte[content.length];
        ByteBuffer received = ByteBuffer.wrap(bytes);
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentDemanded((response, demand, buffer, callback) ->
            {
                received.put(buffer);
                callback.succeeded();
                new Thread(() -> demand.accept(1)).start();
            })
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(content, bytes);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDelayedBeforeContentDemand(Transport transport) throws Exception
    {
        init(transport);

        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setContentLength(content.length);
                response.getOutputStream().write(content);
            }
        });

        byte[] bytes = new byte[content.length];
        ByteBuffer received = ByteBuffer.wrap(bytes);
        AtomicReference<LongConsumer> beforeContentDemandRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Do not demand now.
                    beforeContentDemandRef.set(demand);
                    beforeContentLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer buffer, Callback callback)
                {
                    contentLatch.countDown();
                    received.put(buffer);
                    callback.succeeded();
                    demand.accept(1);
                }
            })
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));
        LongConsumer demand = beforeContentDemandRef.get();

        // Content must not be notified until we demand.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        demand.accept(1);

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(content, bytes);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDelayedBeforeContentDemandWithNoResponseContent(Transport transport) throws Exception
    {
        init(transport);

        scenario.start(new EmptyServerHandler());

        AtomicReference<LongConsumer> beforeContentDemandRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Do not demand now.
                    beforeContentDemandRef.set(demand);
                    beforeContentLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer buffer, Callback callback)
                {
                    contentLatch.countDown();
                    callback.succeeded();
                    demand.accept(1);
                }
            })
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));
        LongConsumer demand = beforeContentDemandRef.get();

        // Content must not be notified until we demand.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        demand.accept(1);

        // Content must not be notified as there is no content.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }
}
