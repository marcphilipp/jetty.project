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

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTimeout4Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutIsCancelledOnSuccess(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.start(new TimeoutHandler(timeout));

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Request request = scenario.client.newRequest(scenario.newURI())
            .body(new InputStreamRequestContent(new ByteArrayInputStream(content)))
            .timeout(2 * timeout, TimeUnit.MILLISECONDS);
        request.send(new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertFalse(result.isFailed());
                assertArrayEquals(content, getContent());
                latch.countDown();
            }
        });

        assertTrue(latch.await(3 * timeout, TimeUnit.MILLISECONDS));

        TimeUnit.MILLISECONDS.sleep(2 * timeout);

        assertNull(request.getAbortCause());
    }
}
