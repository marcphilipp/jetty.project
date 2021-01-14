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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
@ExtendWith(WorkDirExtension.class)
public class HttpClient2Test extends AbstractHttpClientServerTest
{
    public WorkDir testdir;

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testRequestIdleTimeout(Scenario scenario) throws Exception
    {
        long idleTimeout = 1000;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        assertThrows(TimeoutException.class, () ->
                client.newRequest(host, port)
                        .scheme(scenario.getScheme())
                        .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                        .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                        .send());

        // Make another request without specifying the idle timeout, should not fail
        ContentResponse response = client.newRequest(host, port)
                .scheme(scenario.getScheme())
                .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExchangeIsCompleteOnlyWhenBothRequestAndResponseAreComplete(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setContentLength(0);
                response.setStatus(200);
                response.flushBuffer();

                byte[] buffer = new byte[1024];
                InputStream in = request.getInputStream();
                while (true)
                {
                    int read = in.read(buffer);
                    if (read < 0)
                        break;
                }
            }
        });

        // Prepare a big file to upload
        Path targetTestsDir = testdir.getEmptyPathDir();
        Files.createDirectories(targetTestsDir);
        Path file = Paths.get(targetTestsDir.toString(), "http_client_conversation.big");
        try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
            {
                output.write(kb);
            }
        }

        CountDownLatch latch = new CountDownLatch(3);
        AtomicLong exchangeTime = new AtomicLong();
        AtomicLong requestTime = new AtomicLong();
        AtomicLong responseTime = new AtomicLong();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .file(file)
                .onRequestSuccess(request ->
                {
                    requestTime.set(System.nanoTime());
                    latch.countDown();
                })
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        responseTime.set(System.nanoTime());
                        latch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        exchangeTime.set(System.nanoTime());
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertTrue(requestTime.get() <= exchangeTime.get());
        assertTrue(responseTime.get() <= exchangeTime.get());

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);

        Files.delete(file);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCompleteNotInvokedUntilContentConsumed(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[1024]);
            }
        });

        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content, Callback callback)
                    {
                        // Do not notify the callback yet.
                        callbackRef.set(callback);
                        contentLatch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded())
                            completeLatch.countDown();
                    }
                });

        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Make sure the complete event is not emitted.
        assertFalse(completeLatch.await(1, TimeUnit.SECONDS));

        // Consume the content.
        callbackRef.get().succeeded();

        // Now the complete event is emitted.
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHTTP10WithKeepAliveAndNoContentLength(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the headers at this point, then write the content
                response.flushBuffer();
                response.getOutputStream().print("TEST");
            }
        });

        long timeout = 5000;
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .version(HttpVersion.HTTP_1_0)
                .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()))
                .timeout(timeout, TimeUnit.MILLISECONDS);
        FuturePromise<Connection> promise = new FuturePromise<>();
        Destination destination = client.resolveDestination(request);
        destination.newConnection(promise);
        try (Connection connection = promise.get(5, TimeUnit.SECONDS))
        {
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            ContentResponse response = listener.get(2 * timeout, TimeUnit.MILLISECONDS);

            assertEquals(200, response.getStatus());
            // The parser notifies end-of-content and therefore the CompleteListener
            // before closing the connection, so we need to wait before checking
            // that the connection is closed to avoid races.
            Thread.sleep(1000);
            assertTrue(connection.isClosed());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testUnsolicitedResponseBytesFromServer(Scenario scenario) throws Exception
    {
        String response = "" +
                "HTTP/1.1 408 Request Timeout\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        testUnsolicitedBytesFromServer(scenario, response);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testUnsolicitedInvalidBytesFromServer(Scenario scenario) throws Exception
    {
        String response = "ABCDEF";
        testUnsolicitedBytesFromServer(scenario, response);
    }

    private void testUnsolicitedBytesFromServer(Scenario scenario, String bytesFromServer) throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            startClient(scenario, clientConnector ->
            {
                clientConnector.setSelectors(1);
                HttpClientTransportOverHTTP transport = new HttpClientTransportOverHTTP(clientConnector);
                transport.setConnectionPoolFactory(destination ->
                {
                    ConnectionPool connectionPool = new DuplexConnectionPool(destination, 1, destination);
                    connectionPool.preCreateConnections(1);
                    return connectionPool;
                });
                return transport;
            }, null);

            String host = "localhost";
            int port = server.getLocalPort();

            // Resolve the destination which will pre-create a connection.
            HttpDestination destination = client.resolveDestination(new Origin("http", host, port));

            // Accept the connection and send an unsolicited 408.
            try (Socket socket = server.accept())
            {
                OutputStream output = socket.getOutputStream();
                output.write(bytesFromServer.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            // Give some time to the client to process the response.
            Thread.sleep(1000);

            AbstractConnectionPool pool = (AbstractConnectionPool)destination.getConnectionPool();
            assertEquals(0, pool.getConnectionCount());
        }
    }
}
