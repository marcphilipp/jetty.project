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
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncEchoTest extends AbstractAsyncIOServletTest
{

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncEcho(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                System.err.println("Service " + request);

                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady())
                        {
                            int b = input.read();
                            if (b >= 0)
                            {
                                // System.err.printf("0x%2x %s %n", b, Character.isISOControl(b)?"?":(""+(char)b));
                                response.getOutputStream().write(b);
                            }
                            else
                                return;
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable x)
                    {
                    }
                });
            }
        });

        AsyncRequestContent contentProvider = new AsyncRequestContent();
        CountDownLatch clientLatch = new CountDownLatch(1);

        AtomicReference<Result> resultRef = new AtomicReference<>();
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .body(contentProvider)
            .send(new BufferingResponseListener(16 * 1024 * 1024)
            {
                @Override
                public void onComplete(Result result)
                {
                    resultRef.set(result);
                    clientLatch.countDown();
                }
            });

        for (int i = 0; i < 1_000_000; i++)
        {
            contentProvider.offer(BufferUtil.toBuffer("S" + i));
        }
        contentProvider.close();

        assertTrue(clientLatch.await(30, TimeUnit.SECONDS));
        assertThat(resultRef.get().isSucceeded(), Matchers.is(true));
        assertThat(resultRef.get().getResponse().getStatus(), Matchers.equalTo(HttpStatus.OK_200));
    }
}
