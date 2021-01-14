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
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts6Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadHttpIdleTimeoutOverridesIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        long httpIdleTimeout = 2500;
        long idleTimeout = 3 * httpIdleTimeout;
        scenario.httpConfig.setIdleTimeout(httpIdleTimeout);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new BlockingReadHandler(handlerLatch));
        scenario.setServerIdleTimeout(idleTimeout);

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1));
            CountDownLatch resultLatch = new CountDownLatch(1);
            scenario.client.POST(scenario.newURI())
                .body(content)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                        resultLatch.countDown();
                });

            // Blocking read should timeout.
            assertTrue(handlerLatch.await(2 * httpIdleTimeout, TimeUnit.MILLISECONDS));
            // Complete the request.
            content.close();
            assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        }
    }

    private static class BlockingReadHandler extends AbstractHandler
    {
        private final CountDownLatch handlerLatch;

        public BlockingReadHandler(CountDownLatch handlerLatch)
        {
            this.handlerLatch = handlerLatch;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            baseRequest.setHandled(true);
            ServletInputStream input = request.getInputStream();
            assertEquals(0, input.read());
            try
            {
                input.read();
            }
            catch (IOException x)
            {
                handlerLatch.countDown();
                throw x;
            }
        }
    }
}
