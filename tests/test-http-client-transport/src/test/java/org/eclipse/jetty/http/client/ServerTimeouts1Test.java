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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeouts1Test extends AbstractServerTimeoutsTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(scenario, false);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testBlockingReadWithDelayedFirstContentIdleTimeoutFires(scenario, true);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadWithDelayedFirstContentWithUndelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(scenario, false);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncReadWithDelayedFirstContentWithDelayedDispatchIdleTimeoutFires(Transport transport) throws Exception
    {
        init(transport);
        testAsyncReadWithDelayedFirstContentIdleTimeoutFires(scenario, true);
    }

    private void testBlockingReadWithDelayedFirstContentIdleTimeoutFires(TransportScenario scenario, boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // The client did not send the content,
                // idle timeout should result in IOException.
                request.getInputStream().read();
            }
        }, delayDispatch);
    }

    private void testAsyncReadWithDelayedFirstContentIdleTimeoutFires(TransportScenario scenario, boolean delayDispatch) throws Exception
    {
        testReadWithDelayedFirstContentIdleTimeoutFires(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        if (t instanceof TimeoutException)
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);

                        asyncContext.complete();
                    }
                });
            }
        }, delayDispatch);
    }

    private void testReadWithDelayedFirstContentIdleTimeoutFires(TransportScenario scenario, Handler handler, boolean delayDispatch) throws Exception
    {
        scenario.httpConfig.setDelayDispatchUntilContent(delayDispatch);
        CountDownLatch handlerLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    handler.handle(target, jettyRequest, request, response);
                }
                finally
                {
                    handlerLatch.countDown();
                }
            }
        });
        long idleTimeout = 1000;
        scenario.setServerIdleTimeout(idleTimeout);

        CountDownLatch resultLatch = new CountDownLatch(2);
        AsyncRequestContent content = new AsyncRequestContent();
        scenario.client.POST(scenario.newURI())
            .body(content)
            .onResponseSuccess(response ->
            {
                if (response.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                    resultLatch.countDown();
                content.close();
            })
            .send(result -> resultLatch.countDown());

        // The client did not send the content, the request was
        // dispatched, the server should have idle timed it out.
        assertTrue(handlerLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }
}
