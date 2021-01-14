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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Assumptions;
import org.opentest4j.TestAbortedException;

import static org.eclipse.jetty.http.client.Transport.UNIX_SOCKET;
import static org.junit.jupiter.api.Assertions.fail;

abstract class AbstractHttpClientTimeoutTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    protected void assumeRealNetwork(Transport transport)
    {
        Assumptions.assumeTrue(transport != UNIX_SOCKET);
    }

    protected void assumeConnectTimeout(String host, int port, int connectTimeout)
    {
        try (Socket socket = new Socket())
        {
            // Try to connect to a private address in the 10.x.y.z range.
            // These addresses are usually not routed, so an attempt to
            // connect to them will hang the connection attempt, which is
            // what we want to simulate in this test.
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            // Fail the test if we can connect.
            fail("Error: Should not have been able to connect to " + host + ":" + port);
        }
        catch (SocketTimeoutException ignored)
        {
            // Expected timeout during connect, continue the test.
        }
        catch (Throwable x)
        {
            // Abort if any other exception happens.
            throw new TestAbortedException("Not able to validate connect timeout conditions", x);
        }
    }

    protected static class TimeoutHandler extends AbstractHandler
    {
        private final long timeout;

        public TimeoutHandler(long timeout)
        {
            this.timeout = timeout;
        }

        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            try
            {
                TimeUnit.MILLISECONDS.sleep(timeout);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
            catch (InterruptedException x)
            {
                throw new ServletException(x);
            }
        }
    }
}
