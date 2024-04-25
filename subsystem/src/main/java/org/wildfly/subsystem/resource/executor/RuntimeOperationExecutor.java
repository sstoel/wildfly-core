/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.subsystem.resource.executor;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.dmr.ModelNode;

/**
 * Encapsulates the execution of a runtime operation.
 * @param <C> the operation execution context.
 * @author Paul Ferraro
 */
public interface RuntimeOperationExecutor<C> {

    /**
     * Executes the specified executable against the specified operation context.
     * @param context an operation context
     * @param operation operation model for resolving operation parameters
     * @param executable the contextual executable object
     * @return the result of the execution (possibly null).
     * @throws OperationFailedException if execution fails
     */
    ModelNode execute(OperationContext context, ModelNode operation, RuntimeOperation<C> executable) throws OperationFailedException;
}
