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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts5Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithMinimumDataRateAboveLimit(Transport transport) throws Exception
    {
        init(transport);
        int bytesPerSecond = 20;
        scenario.httpConfig.setMinRequestDataRate(bytesPerSecond);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }
                handlerLatch.countDown();
            }
        });

        AsyncRequestContent content = new AsyncRequestContent();
        CountDownLatch resultLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.getResponse().getStatus() == HttpStatus.OK_200)
                    resultLatch.countDown();
            });

        for (int i = 0; i < 3; ++i)
        {
            content.offer(ByteBuffer.allocate(bytesPerSecond * 2));
            Thread.sleep(2500);
        }
        content.close();

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }
}
