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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTimeout13Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testFirstRequestTimeoutAfterSecondRequestCompletes(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 2000;
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().startsWith("/one"))
                {
                    try
                    {
                        Thread.sleep(3 * timeout);
                    }
                    catch (InterruptedException x)
                    {
                        throw new InterruptedIOException();
                    }
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/one")
            .timeout(2 * timeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                if (result.isFailed() && result.getFailure() instanceof TimeoutException)
                    latch.countDown();
            });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .path("/two")
            .timeout(timeout, TimeUnit.MILLISECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
