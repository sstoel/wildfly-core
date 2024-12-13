/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.platform.mbean;

import java.lang.management.ManagementFactory;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.operations.validation.LongRangeValidator;
import org.jboss.as.controller.operations.validation.ParametersValidator;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Executes the {@link java.lang.management.ThreadMXBean#getThreadCpuTime(long)} method.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class ThreadMXBeanCpuTimeHandler implements OperationStepHandler {

    static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(PlatformMBeanConstants.GET_THREAD_CPU_TIME, PlatformMBeanUtil.getResolver(PlatformMBeanConstants.THREADING))
            .setParameters(CommonAttributes.ID)
            .setReplyType(ModelType.LONG)
            .setRuntimeOnly()
            .setReadOnly()
            .build();

    public static final ThreadMXBeanCpuTimeHandler INSTANCE = new ThreadMXBeanCpuTimeHandler();

    private final ParametersValidator validator = new ParametersValidator();

    private ThreadMXBeanCpuTimeHandler() {
        validator.registerValidator(PlatformMBeanConstants.ID, LongRangeValidator.POSITIVE);
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        validator.validate(operation);

        try {
            long id = operation.require(PlatformMBeanConstants.ID).asLong();
            context.getResult().set(ManagementFactory.getThreadMXBean().getThreadCpuTime(id));
        } catch (UnsupportedOperationException e) {
            throw new OperationFailedException(e.toString());
        }
    }
}
