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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts4Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithMinimumDataRateBelowLimit(Transport transport) throws Exception
    {
        init(transport);
        int bytesPerSecond = 20;
        scenario.requestLog.clear();
        scenario.httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    baseRequest.setHandled(true);
                    ServletInputStream input = request.getInputStream();
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                }
                catch (BadMessageException x)
                {
                    handlerLatch.countDown();
                    throw x;
                }
            }
        });

        AsyncRequestContent content = new AsyncRequestContent();
        BlockingQueue<Object> results = new BlockingArrayQueue<>();
        scenario.client.newRequest(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isFailed())
                    results.offer(result.getFailure());
                else
                    results.offer(result.getResponse().getStatus());
            });

        for (int i = 0; i < 3; ++i)
        {
            content.offer(ByteBuffer.allocate(bytesPerSecond / 2));
            Thread.sleep(2500);
        }
        content.close();

        assertThat(scenario.requestLog.poll(5, TimeUnit.SECONDS), containsString(" 408"));

        // Request should timeout.
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));

        Object result = results.poll(5, TimeUnit.SECONDS);
        assertNotNull(result);
        if (result instanceof Integer)
            assertThat(result, is(408));
        else
            assertThat(result, instanceOf(Throwable.class));
    }
}
