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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HttpClientTimeout12Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testTimeoutCancelledWhenSendingThrowsException(Transport transport) throws Exception
    {
        assumeRealNetwork(transport);
        init(transport);

        scenario.start(new EmptyServerHandler());

        long timeout = 1000;
        String uri = "badscheme://0.0.0.1";
        if (scenario.getNetworkConnectorLocalPort().isPresent())
            uri += ":" + scenario.getNetworkConnectorLocalPort().get();
        Request request = scenario.client.newRequest(uri);

        // TODO: assert a more specific Throwable
        assertThrows(Exception.class, () ->
        {
            request.timeout(timeout, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                });
        });

        Thread.sleep(2 * timeout);

        // If the task was not cancelled, it aborted the request.
        assertNull(request.getAbortCause());
    }
}
