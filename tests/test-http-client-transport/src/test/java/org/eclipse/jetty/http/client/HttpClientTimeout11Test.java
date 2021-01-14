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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTimeout11Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testVeryShortTimeout(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .timeout(1, TimeUnit.MILLISECONDS) // Very short timeout
            .send(result -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
