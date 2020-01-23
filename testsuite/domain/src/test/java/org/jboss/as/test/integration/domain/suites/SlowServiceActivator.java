/*
 * Copyright 2020 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.test.integration.domain.suites;

import java.util.concurrent.TimeUnit;

import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.msc.service.ServiceActivator;
import org.jboss.msc.service.ServiceActivatorContext;
import org.jboss.msc.service.ServiceRegistryException;

/**
 * Simple service activator that delays the activation some seconds.
 *
 * @author <a href="mailto:yborgess@redhat.com">Yeray Borges</a>
 */
public class SlowServiceActivator implements ServiceActivator {
    @Override
    public void activate(ServiceActivatorContext serviceActivatorContext) throws ServiceRegistryException {
        try {
            long timeout = System.currentTimeMillis() + TimeoutUtil.adjust(20)*1000;
            while (System.currentTimeMillis() - timeout < 0) {
                TimeUnit.SECONDS.sleep(1);
            }
        } catch (InterruptedException e) {
            throw new ServiceRegistryException(e);
        }
    }
}
