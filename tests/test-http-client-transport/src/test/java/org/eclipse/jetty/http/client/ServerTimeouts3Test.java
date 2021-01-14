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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.http.client.Transport.UNIX_SOCKET;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts3Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncWriteIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        // TODO work out why this test fails for UNIX_SOCKET
        Assumptions.assumeFalse(scenario.transport == UNIX_SOCKET);

        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                ServletOutputStream output = response.getOutputStream();
                output.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (output.isReady())
                            output.write(new byte[64 * 1024 * 1024]);
                    }

                    @Override
                    public void onError(Throwable failure)
                    {
                        if (failure instanceof TimeoutException)
                            handlerLatch.countDown();

                        asyncContext.complete();
                    }
                });
            }
        });
        long idleTimeout = 2500;
        scenario.setServerIdleTimeout(idleTimeout);

        BlockingQueue<Callback> callbacks = new LinkedBlockingQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentAsync((response, content, callback) ->
            {
                // Do not succeed the callback so the server will block writing.
                callbacks.offer(callback);
            })
            .send(result ->
            {
                if (result.isFailed())
                    resultLatch.countDown();
            });

        // Async write should timeout.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // After the server stopped sending, consume on the client to read the early EOF.
        while (true)
        {
            Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
            if (callback == null)
                break;
            callback.succeeded();
        }
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }
}
