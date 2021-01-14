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

import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HttpClientTimeout7Test extends AbstractHttpClientTimeoutTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        long timeout = 1000;
        scenario.startServer(new TimeoutHandler(2 * timeout));

        AtomicBoolean sslIdle = new AtomicBoolean();
        SslContextFactory.Client sslContextFactory = scenario.newClientSslContextFactory();
        scenario.client = new HttpClient(scenario.provideClientTransport(transport, sslContextFactory))
        {
            @Override
            public ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory.Client sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(byteBufferPool, executor, endPoint, engine)
                        {
                            @Override
                            protected boolean onReadTimeout(Throwable timeout)
                            {
                                sslIdle.set(true);
                                return super.onReadTimeout(timeout);
                            }
                        };
                    }
                };
            }
        };
        scenario.client.setIdleTimeout(timeout);
        scenario.client.start();

        assertThrows(TimeoutException.class, () ->
        {
            scenario.client.newRequest(scenario.newURI())
                .send();
        });
        assertFalse(sslIdle.get());
    }
}
