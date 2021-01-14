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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTimeout10Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void retryAfterConnectTimeout(Transport transport) throws Exception
    {
        assumeRealNetwork(transport);
        init(transport);

        final String host = "10.255.255.1";
        final int port = 80;
        int connectTimeout = 1000;
        assumeConnectTimeout(host, port, connectTimeout);

        scenario.start(new EmptyServerHandler());
        HttpClient client = scenario.client;
        client.stop();
        client.setConnectTimeout(connectTimeout);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest(host, port);
        request.scheme(scenario.getScheme())
            .send(result ->
            {
                if (result.isFailed())
                {
                    // Retry
                    client.newRequest(host, port)
                        .scheme(scenario.getScheme())
                        .send(retryResult ->
                        {
                            if (retryResult.isFailed())
                                latch.countDown();
                        });
                }
            });

        assertTrue(latch.await(3 * connectTimeout, TimeUnit.MILLISECONDS));
        assertNotNull(request.getAbortCause());
    }
}
