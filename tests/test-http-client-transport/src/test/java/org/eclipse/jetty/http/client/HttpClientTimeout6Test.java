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

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTimeout6Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutIsCancelledOnSuccessWithExplicitConnection(Transport transport) throws Exception
    {
        assumeRealNetwork(transport);
        init(transport);

        long timeout = 1000;
        scenario.start(new TimeoutHandler(timeout));

        Request request = scenario.client.newRequest(scenario.newURI()).timeout(2 * timeout, TimeUnit.MILLISECONDS);
        CountDownLatch latch = new CountDownLatch(1);
        Destination destination = scenario.client.resolveDestination(request);
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            connection.send(request, result ->
            {
                Response response = result.getResponse();
                assertEquals(200, response.getStatus());
                assertFalse(result.isFailed());
                latch.countDown();
            });

            assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

            TimeUnit.MILLISECONDS.sleep(2 * timeout);

            assertNull(request.getAbortCause());
        }
    }

}
