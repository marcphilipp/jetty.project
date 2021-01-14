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

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.junit.jupiter.api.Assumptions;

import static org.eclipse.jetty.http.client.Transport.FCGI;
import static org.junit.jupiter.api.Assertions.assertNotNull;

abstract class AbstractAsyncIOServletTest extends AbstractTest<AbstractAsyncIOServletTest.AsyncTransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        // Skip FCGI for now, not much interested in its server-side behavior.
        Assumptions.assumeTrue(transport != FCGI);
        setScenario(new AsyncTransportScenario(transport));
    }

    public static class AsyncTransportScenario extends TransportScenario
    {
        public static final ThreadLocal<RuntimeException> scope = new ThreadLocal<>();

        public AsyncTransportScenario(Transport transport) throws IOException
        {
            super(transport);
        }

        @Override
        public void startServer(Handler handler) throws Exception
        {
            if (handler == context)
            {
                // Add this listener before the context is started, so it's durable.
                context.addEventListener(new ContextHandler.ContextScopeListener()
                {
                    @Override
                    public void enterScope(Context context, Request request, Object reason)
                    {
                        checkScope();
                        scope.set(new RuntimeException());
                    }

                    @Override
                    public void exitScope(Context context, Request request)
                    {
                        assertScope();
                        scope.set(null);
                    }
                });
            }
            super.startServer(handler);
        }

        public void assertScope()
        {
            assertNotNull(scope.get(), "Not in scope");
        }

        public void checkScope()
        {
            RuntimeException callScope = scope.get();
            if (callScope != null)
                throw callScope;
        }

        @Override
        public void stopServer() throws Exception
        {
            checkScope();
            scope.set(null);
            super.stopServer();
        }
    }
}
