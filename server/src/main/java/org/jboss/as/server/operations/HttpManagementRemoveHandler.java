/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.server.operations;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_INTERFACE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NATIVE_INTERFACE;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.domain.management.access.RbacSanityCheckOperation;
import org.jboss.as.remoting.management.ManagementRemotingServices;
import org.jboss.dmr.ModelNode;

/**
 * Removes the HTTP management interface.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class HttpManagementRemoveHandler extends ReloadRequiredRemoveStepHandler {

    public static final HttpManagementRemoveHandler INSTANCE = new HttpManagementRemoveHandler();

    private HttpManagementRemoveHandler() {
        super();
    }

    @Override
    protected void performRemove(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        RbacSanityCheckOperation.addOperation(context);
        final PathAddress nativeAddress = context.getCurrentAddress().getParent().append(PathElement.pathElement(MANAGEMENT_INTERFACE, NATIVE_INTERFACE));
        context.addStep((OperationContext opContext, ModelNode op) -> {
            ManagementRemotingServices.isManagementResourceRemoveable(opContext, nativeAddress);
        }, OperationContext.Stage.MODEL, false);
        super.performRemove(context, operation, model);
    }

    @Override
    protected boolean requiresRuntime(OperationContext context) {
        return context.getProcessType() != ProcessType.EMBEDDED_SERVER || context.getRunningMode() != RunningMode.ADMIN_ONLY;
    }
}
