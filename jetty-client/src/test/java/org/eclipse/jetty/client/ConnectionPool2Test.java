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

package org.eclipse.jetty.client;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionPool2Test
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    public static Stream<ConnectionPoolFactory> pools()
    {
        return Stream.concat(poolsNoRoundRobin(),
            Stream.of(new ConnectionPoolFactory("round-robin", destination -> new RoundRobinConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination))));
    }

    public static Stream<ConnectionPoolFactory> poolsNoRoundRobin()
    {
        return Stream.of(
            new ConnectionPoolFactory("duplex", destination -> new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination)),
            new ConnectionPoolFactory("duplex-maxDuration", destination ->
            {
                DuplexConnectionPool pool = new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination);
                pool.setMaxDuration(10);
                return pool;
            }),
            new ConnectionPoolFactory("multiplex", destination -> new MultiplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1)),
            new ConnectionPoolFactory("random", destination -> new RandomConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1))
        );
    }

    private void startClient(ConnectionPool.Factory factory) throws Exception
    {
        ClientConnector connector = new ClientConnector();
        connector.setSelectors(1);
        HttpClientTransport transport = new HttpClientTransportOverHTTP(connector);
        transport.setConnectionPoolFactory(factory);
        client = new HttpClient(transport);
        client.start();
    }

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void disposeServer() throws Exception
    {
        connector = null;
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }

    @AfterEach
    public void disposeClient() throws Exception
    {
        if (client != null)
        {
            client.stop();
            client = null;
        }
    }

    @ParameterizedTest
    @MethodSource("poolsNoRoundRobin")
    public void testQueuedRequestsDontOpenTooManyConnections(ConnectionPoolFactory factory) throws Exception
    {
        // Round robin connection pool does open a few more
        // connections than expected, exclude it from this test.

        startServer(new EmptyServerHandler());

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        HttpClientTransport transport = new HttpClientTransportOverHTTP(clientConnector);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        long delay = 1000;
        client.setSocketAddressResolver(new SocketAddressResolver.Sync()
        {
            @Override
            public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
            {
                client.getExecutor().execute(() ->
                {
                    try
                    {
                        Thread.sleep(delay);
                        super.resolve(host, port, promise);
                    }
                    catch (InterruptedException x)
                    {
                        promise.failed(x);
                    }
                });
            }
        });
        client.start();

        CountDownLatch latch = new CountDownLatch(2);
        client.newRequest("localhost", connector.getLocalPort())
            .path("/one")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });
        Thread.sleep(delay / 2);
        client.newRequest("localhost", connector.getLocalPort())
            .path("/two")
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * delay, TimeUnit.MILLISECONDS));
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(2, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsWithSlowAddressResolver(ConnectionPoolFactory factory) throws Exception
    {
        // ConnectionPools may open a few more connections than expected.

        startServer(new EmptyServerHandler());

        int count = 500;
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        QueuedThreadPool clientThreads = new QueuedThreadPool(2 * count);
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HttpClientTransport transport = new HttpClientTransportOverHTTP(clientConnector);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.setExecutor(clientThreads);
        client.setMaxConnectionsPerDestination(2 * count);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync()
        {
            @Override
            public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
            {
                client.getExecutor().execute(() ->
                {
                    try
                    {
                        Thread.sleep(100);
                        super.resolve(host, port, promise);
                    }
                    catch (InterruptedException x)
                    {
                        promise.failed(x);
                    }
                });
            }
        });
        client.start();

        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            clientThreads.execute(() -> client.newRequest("localhost", connector.getLocalPort())
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                }));
        }

        assertTrue(latch.await(count, TimeUnit.SECONDS));
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsAllBlockedOnServerWithLargeConnectionPool(ConnectionPoolFactory factory) throws Exception
    {
        int count = 50;
        testConcurrentRequestsAllBlockedOnServer(factory, count, 2 * count);
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConcurrentRequestsAllBlockedOnServerWithExactConnectionPool(ConnectionPoolFactory factory) throws Exception
    {
        int count = 50;
        testConcurrentRequestsAllBlockedOnServer(factory, count, count);
    }

    private  void testConcurrentRequestsAllBlockedOnServer(ConnectionPoolFactory factory, int count, int maxConnections) throws Exception
    {
        CyclicBarrier barrier = new CyclicBarrier(count);

        QueuedThreadPool serverThreads = new QueuedThreadPool(2 * count);
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                try
                {
                    barrier.await();
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
            }
        });
        server.start();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        QueuedThreadPool clientThreads = new QueuedThreadPool(2 * count);
        clientThreads.setName("client");
        HttpClientTransport transport = new HttpClientTransportOverHTTP(clientConnector);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.setExecutor(clientThreads);
        client.setMaxConnectionsPerDestination(maxConnections);
        client.start();

        // Send N requests to the server, all waiting on the server.
        // This should open N connections, and the test verifies that
        // all N are sent (i.e. the client does not keep any queued).
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            int id = i;
            clientThreads.execute(() -> client.newRequest("localhost", connector.getLocalPort())
                .path("/" + id)
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                }));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "server requests " + barrier.getNumberWaiting() + "<" + count + " - client: " + client.dump());
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertThat(connectionPool.getConnectionCount(), Matchers.greaterThanOrEqualTo(count));
    }

    @Test
    public void testMaxDurationConnectionsWithConstrainedPool() throws Exception
    {
        // ConnectionPool may NOT open more connections than expected because
        // it is constrained to a single connection in this test.

        final int maxConnections = 1;
        final int maxDuration = 30;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("duplex-maxDuration", destination ->
        {
            // Constrain the max pool size to 1.
            DuplexConnectionPool pool = new DuplexConnectionPool(destination, maxConnections, destination)
            {
                @Override
                protected void onCreated(Connection connection)
                {
                    poolCreateCounter.incrementAndGet();
                }

                @Override
                protected void removed(Connection connection)
                {
                    poolRemoveCounter.incrementAndGet();
                }
            };
            pool.setMaxDuration(maxDuration);
            return pool;
        });

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.start();

        // Use the connection pool 5 times with a delay that is longer than the max duration in between each time.
        for (int i = 0; i < 5; i++)
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), Matchers.is(200));

            Thread.sleep(maxDuration * 2);
        }

        // Check that the pool created 5 and removed 4 connections;
        // it must be exactly 4 removed b/c each cycle of the loop
        // can only open 1 connection as the pool is constrained to
        // maximum 1 connection.
        assertThat(poolCreateCounter.get(), Matchers.is(5));
        assertThat(poolRemoveCounter.get(), Matchers.is(4));
    }

    @Test
    public void testMaxDurationConnectionsWithUnconstrainedPool() throws Exception
    {
        // ConnectionPools may open a few more connections than expected.

        final int maxDuration = 30;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("duplex-maxDuration", destination ->
        {
            DuplexConnectionPool pool = new DuplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination)
            {
                @Override
                protected void onCreated(Connection connection)
                {
                    poolCreateCounter.incrementAndGet();
                }

                @Override
                protected void removed(Connection connection)
                {
                    poolRemoveCounter.incrementAndGet();
                }
            };
            pool.setMaxDuration(maxDuration);
            return pool;
        });

        startServer(new EmptyServerHandler());

        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.start();

        // Use the connection pool 5 times with a delay that is longer than the max duration in between each time.
        for (int i = 0; i < 5; i++)
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), Matchers.is(200));

            Thread.sleep(maxDuration * 2);
        }

        // Check that the pool created 5 and removed at least 4 connections;
        // it can be more than 4 removed b/c each cycle of the loop may
        // open more than 1 connection as the pool is not constrained.
        assertThat(poolCreateCounter.get(), Matchers.is(5));
        assertThat(poolRemoveCounter.get(), Matchers.greaterThanOrEqualTo(4));
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testConnectionMaxUsage(ConnectionPoolFactory factory) throws Exception
    {
        startServer(new EmptyServerHandler());

        int maxUsageCount = 2;
        startClient(destination ->
        {
            AbstractConnectionPool connectionPool = (AbstractConnectionPool)factory.factory.newConnectionPool(destination);
            connectionPool.setMaxUsageCount(maxUsageCount);
            return connectionPool;
        });
        client.setMaxConnectionsPerDestination(1);

        // Send first request, we are within the max usage count.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        HttpDestination destination = (HttpDestination)client.getDestinations().get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();

        assertEquals(0, connectionPool.getActiveConnectionCount());
        assertEquals(1, connectionPool.getIdleConnectionCount());
        assertEquals(1, connectionPool.getConnectionCount());

        // Send second request, max usage count will be reached,
        // the only connection must be closed.
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());

        assertEquals(0, connectionPool.getActiveConnectionCount());
        assertEquals(0, connectionPool.getIdleConnectionCount());
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @MethodSource("pools")
    public void testIdleTimeoutNoRequests(ConnectionPoolFactory factory) throws Exception
    {
        startServer(new EmptyServerHandler());
        startClient(destination ->
        {
            try
            {
                ConnectionPool connectionPool = factory.factory.newConnectionPool(destination);
                connectionPool.preCreateConnections(1).get();
                return connectionPool;
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        // Trigger the creation of a destination, that will create the connection pool.
        HttpDestination destination = client.resolveDestination(new Origin("http", "localhost", connector.getLocalPort()));
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(1, connectionPool.getConnectionCount());

        // Wait for the pre-created connections to idle timeout.
        Thread.sleep(idleTimeout + idleTimeout / 2);

        assertEquals(0, connectionPool.getConnectionCount());
    }

    private static class ConnectionPoolFactory
    {
        private final String name;
        private final ConnectionPool.Factory factory;

        private ConnectionPoolFactory(String name, ConnectionPool.Factory factory)
        {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
