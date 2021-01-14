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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts8Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleTimeoutBeforeReadIsIgnored(Transport transport) throws Exception
    {
        init(transport);
        long idleTimeout = 1000;
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    Thread.sleep(idleTimeout + idleTimeout / 2);
                    IO.copy(request.getInputStream(), response.getOutputStream());
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });
        scenario.setServerIdleTimeout(idleTimeout);

        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        byte[] data1 = new byte[data.length / 2];
        System.arraycopy(data, 0, data1, 0, data1.length);
        byte[] data2 = new byte[data.length - data1.length];
        System.arraycopy(data, data1.length, data2, 0, data2.length);
        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(data1));
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path(scenario.servletPath)
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        // Wait for the server application to block reading.
        Thread.sleep(2 * idleTimeout);
        content.offer(ByteBuffer.wrap(data2));
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
