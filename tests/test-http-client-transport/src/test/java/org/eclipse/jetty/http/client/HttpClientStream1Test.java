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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientStream1Test extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testFileUpload(Transport transport) throws Exception
    {
        init(transport);
        // Prepare a big file to upload
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        Path upload = Paths.get(targetTestsDir.toString(), "http_client_upload.big");
        try (OutputStream output = Files.newOutputStream(upload, StandardOpenOption.CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
            {
                output.write(kb);
            }
        }

        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentLength(0);
                response.flushBuffer();

                InputStream in = request.getInputStream();
                byte[] buffer = new byte[1024];
                while (true)
                {
                    int read = in.read(buffer);
                    if (read < 0)
                        break;
                }
            }
        });

        AtomicLong requestTime = new AtomicLong();
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .file(upload)
            .onRequestSuccess(request -> requestTime.set(System.nanoTime()))
            .timeout(30, TimeUnit.SECONDS)
            .send();
        long responseTime = System.nanoTime();

        assertEquals(200, response.getStatus());
        assertTrue(requestTime.get() <= responseTime);

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownload(Transport transport) throws Exception
    {
        init(transport);
        byte[] data = new byte[128 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        int length = 0;
        while (input.read() == value)
        {
            if (length % 100 == 0)
                Thread.sleep(1);
            ++length;
        }

        assertEquals(data.length, length);

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.isFailed());
        assertSame(response, result.getResponse());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadOfUTF8Content(Transport transport) throws Exception
    {
        init(transport);
        byte[] data = new byte[]{(byte)0xC3, (byte)0xA8}; // UTF-8 representation of &egrave;
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        for (byte b : data)
        {
            int read = input.read();
            assertTrue(read >= 0);
            assertEquals(b & 0xFF, read);
        }

        assertEquals(-1, input.read());

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.isFailed());
        assertSame(response, result.getResponse());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithFailure(Transport transport) throws Exception
    {
        init(transport);
        byte[] data = new byte[64 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // Say we want to send this much...
                response.setContentLength(2 * data.length);
                // ...but write only half...
                response.getOutputStream().write(data);
                // ...then shutdown output
                baseRequest.getHttpChannel().getEndPoint().shutdownOutput();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        AtomicInteger length = new AtomicInteger();

        assertThrows(IOException.class, () ->
        {
            while (input.read() == value)
            {
                if (length.incrementAndGet() % 100 == 0)
                    Thread.sleep(1);
            }
        });

        assertThat(length.get(), lessThanOrEqualTo(data.length));

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isFailed());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerClosedBeforeReading(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        InputStream stream = listener.getInputStream();
        // Close the stream immediately.
        stream.close();

        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .body(new BytesRequestContent(new byte[]{0, 1, 2, 3}))
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        assertThrows(AsynchronousCloseException.class, stream::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerClosedBeforeContent(Transport transport) throws Exception
    {
        init(transport);
        AtomicReference<AsyncContext> contextRef = new AtomicReference<>();
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                contextRef.set(request.startAsync());
                response.flushBuffer();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                super.onContent(response, content, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        latch.countDown();
                        callback.failed(x);
                    }
                });
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        InputStream input = listener.getInputStream();
        input.close();

        AsyncContext asyncContext = contextRef.get();
        asyncContext.getResponse().getOutputStream().write(new byte[1024]);
        asyncContext.complete();

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerClosedWhileWaiting(Transport transport) throws Exception
    {
        init(transport);
        byte[] chunk1 = new byte[]{0, 1};
        byte[] chunk2 = new byte[]{2, 3};
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setContentLength(chunk1.length + chunk2.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();
                output.write(chunk2);
            }
        });

        CountDownLatch failedLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                super.onContent(response, content, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        failedLatch.countDown();
                        callback.failed(x);
                    }
                });
                contentLatch.countDown();
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait until we get some content.
        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Close the stream.
        InputStream stream = listener.getInputStream();
        stream.close();

        // Make sure that the callback has been invoked.
        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerFailedWhileWaiting(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] data = new byte[1024];
                response.setContentLength(data.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(data);
            }
        });

        CountDownLatch failedLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                super.onContent(response, content, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        failedLatch.countDown();
                        callback.failed(x);
                    }
                });
                contentLatch.countDown();
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait until we get some content.
        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Abort the response.
        response.abort(new Exception());

        // Make sure that the callback has been invoked.
        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerFailedBeforeResponse(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());
        String uri = scenario.newURI();
        scenario.server.stop();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        // Connect to the wrong port
        scenario.client.newRequest(uri)
            .scheme(scenario.getScheme())
            .send(listener);
        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamContentProviderThrowingWhileReading(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] data = new byte[]{0, 1, 2, 3};
        ExecutionException e = assertThrows(ExecutionException.class, () ->
            scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .body(new InputStreamRequestContent(new InputStream()
                {
                    private int index = 0;

                    @Override
                    public int read()
                    {
                        // Will eventually throw ArrayIndexOutOfBounds
                        return data[index++];
                    }
                }, data.length / 2))
                .timeout(5, TimeUnit.SECONDS)
                .send());
        assertThat(e.getCause(), instanceOf(ArrayIndexOutOfBoundsException.class));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithCloseBeforeContent(Transport transport) throws Exception
    {
        init(transport);
        byte[] data = new byte[128 * 1024];
        byte value = 3;
        Arrays.fill(data, value);
        CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.flushBuffer();

                try
                {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);
        input.close();

        latch.countDown();

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithCloseMiddleOfContent(Transport transport) throws Exception
    {
        init(transport);
        byte[] data1 = new byte[1024];
        byte[] data2 = new byte[1024];
        CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data1);
                response.flushBuffer();

                try
                {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data2);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        for (byte datum1 : data1)
        {
            assertEquals(datum1, input.read());
        }

        input.close();

        latch.countDown();

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithCloseEndOfContent(Transport transport) throws Exception
    {
        init(transport);
        byte[] data = new byte[1024];
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
                response.flushBuffer();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        for (byte datum : data)
        {
            assertEquals(datum, input.read());
        }

        // Read EOF
        assertEquals(-1, input.read());

        input.close();

        // Must not throw
        assertEquals(-1, input.read());
    }
}
