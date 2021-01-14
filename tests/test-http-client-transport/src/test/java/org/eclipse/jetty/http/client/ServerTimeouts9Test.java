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
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts9Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingWriteWithMinimumDataRateBelowLimit(Transport transport) throws Exception
    {
        // This test needs a large write to stall the server, and a slow reading client.
        // In HTTP/1.1, when using the loopback interface, the buffers are so large that
        // it would require a very large write (32 MiB) and a lot of time for this test
        // to pass. On the first writes, the server fills in the large buffers with a lot
        // of bytes (about 4 MiB), and so it would take a lot of time for the client to
        // read those bytes and eventually produce a write rate that will make the server
        // fail; and the write should be large enough to _not_ complete before the rate
        // is below the minimum.
        // In HTTP/2, we force the flow control window to be small, so that the server
        // stalls almost immediately without having written many bytes, so that the test
        // completes quickly.
        Assumptions.assumeTrue(transport.isHttp2Based());

        init(transport);

        int bytesPerSecond = 16 * 1024;
        scenario.httpConfig.setMinResponseDataRate(bytesPerSecond);
        CountDownLatch serverLatch = new CountDownLatch(1);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    ServletOutputStream output = response.getOutputStream();
                    output.write(new byte[8 * 1024 * 1024]);
                }
                catch (IOException x)
                {
                    serverLatch.countDown();
                }
            }
        });
        ((HttpClientTransportOverHTTP2)scenario.client.getTransport()).getHTTP2Client().setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);

        // Setup the client to read slower than the min data rate.
        BlockingQueue<Object> objects = new LinkedBlockingQueue<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .onResponseContentAsync((response, content, callback) ->
            {
                objects.offer(content.remaining());
                objects.offer(callback);
            })
            .send(result ->
            {
                objects.offer(-1);
                objects.offer(Callback.NOOP);
                if (result.isFailed())
                    clientLatch.countDown();
            });

        long readRate = bytesPerSecond / 2;
        while (true)
        {
            int bytes = (Integer)objects.poll(5, TimeUnit.SECONDS);
            if (bytes < 0)
                break;
            long ms = bytes * 1000L / readRate;
            Thread.sleep(ms);
            Callback callback = (Callback)objects.poll();
            callback.succeeded();
        }

        assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(15, TimeUnit.SECONDS));
    }
}
