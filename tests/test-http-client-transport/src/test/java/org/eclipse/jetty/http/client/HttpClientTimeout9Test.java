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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpClientTimeout9Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConnectTimeoutIsCancelledByShorterRequestTimeout(Transport transport) throws Exception
    {
        assumeRealNetwork(transport);
        init(transport);

        String host = "10.255.255.1";
        int port = 80;
        int connectTimeout = 2000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        HttpClient client = scenario.client;
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(2);
        Request request = client.newRequest(host, port);
        request.scheme(scenario.getScheme())
            .timeout(connectTimeout / 2, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                completes.incrementAndGet();
                latch.countDown();
            });

        assertFalse(latch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertEquals(1, completes.get());
        assertNotNull(request.getAbortCause());
    }
}
