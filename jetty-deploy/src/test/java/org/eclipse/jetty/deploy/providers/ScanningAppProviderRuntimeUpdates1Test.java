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

package org.eclipse.jetty.deploy.providers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.condition.OS.WINDOWS;

/**
 * Similar in scope to {@link ScanningAppProviderStartupTest}, except is concerned with the modification of existing
 * deployed webapps due to incoming changes identified by the {@link ScanningAppProvider}.
 */
@ExtendWith(WorkDirExtension.class)
public class ScanningAppProviderRuntimeUpdates1Test
{
    private static final Logger LOG = LoggerFactory.getLogger(ScanningAppProviderRuntimeUpdates1Test.class);

    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;
    private final AtomicInteger _scans = new AtomicInteger();
    private int _providers;

    @BeforeEach
    public void setupEnvironment() throws Exception
    {
        testdir.ensureEmpty();
        Resource.setDefaultUseCaches(false);

        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();

        // monitor tick
        DeploymentManager dm = jetty.getServer().getBean(DeploymentManager.class);
        for (AppProvider provider : dm.getAppProviders())
        {
            if (provider instanceof ScanningAppProvider)
            {
                _providers++;
                ((ScanningAppProvider)provider).addScannerListener(new Scanner.ScanCycleListener()
                {
                    @Override
                    public void scanEnded(int cycle)
                    {
                        _scans.incrementAndGet();
                    }
                });
            }
        }
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }

    public void waitForDirectoryScan()
    {
        int scan = _scans.get() + (2 * _providers);
        do
        {
            try
            {
                Thread.sleep(200);
            }
            catch (InterruptedException e)
            {
                LOG.warn("Sleep failed", e);
            }
        }
        while (_scans.get() < scan);
    }

    /**
     * Simple webapp deployment after startup of server.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testAfterStartupContext() throws IOException
    {
        jetty.copyWebapp("foo-webapp-1.war", "foo.war");
        jetty.copyWebapp("foo.xml", "foo.xml");

        waitForDirectoryScan();
        waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");
    }
}
