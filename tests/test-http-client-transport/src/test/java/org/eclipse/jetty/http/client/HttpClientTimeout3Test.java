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

import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTimeout3Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutOnQueuedRequest(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.start(new TimeoutHandler(3 * timeout));

        // Only one connection so requests get queued
        scenario.client.setMaxConnectionsPerDestination(1);

        // The first request has a long timeout
        final CountDownLatch firstLatch = new CountDownLatch(1);
        Request request = scenario.client.newRequest(scenario.newURI())
            .timeout(4 * timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            assertFalse(result.isFailed());
            firstLatch.countDown();
        });

        // Second request has a short timeout and should fail in the queue
        final CountDownLatch secondLatch = new CountDownLatch(1);
        request = scenario.client.newRequest(scenario.newURI())
            .timeout(timeout, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            assertTrue(result.isFailed());
            secondLatch.countDown();
        });

        assertTrue(secondLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
        // The second request must fail before the first request has completed
        assertTrue(firstLatch.getCount() > 0);
        assertTrue(firstLatch.await(5 * timeout, TimeUnit.MILLISECONDS));
    }
}
