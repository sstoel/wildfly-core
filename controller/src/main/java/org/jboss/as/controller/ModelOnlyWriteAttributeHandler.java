/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import java.util.Collection;

import org.jboss.dmr.ModelNode;

/**
 * A {@code write-attribute} handler that simply validates the attribute value and stores it in the model.
 *
 * @author Brian Stansberry (c) 2012 Red Hat Inc.
 */
public class ModelOnlyWriteAttributeHandler extends AbstractWriteAttributeHandler<Void> {

    public ModelOnlyWriteAttributeHandler(AttributeDefinition... attributeDefinitions) {
        super(attributeDefinitions);
    }

    public ModelOnlyWriteAttributeHandler(Collection<AttributeDefinition> attributeDefinitions) {
        super(attributeDefinitions);
    }

    @Override
    protected final boolean requiresRuntime(OperationContext context) {
        return false;
    }

    @Override
    protected boolean applyUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode resolvedValue, ModelNode currentValue, HandbackHolder<Void> handbackHolder) throws OperationFailedException {
        // should not be called as requiresRuntime returns false
        throw new UnsupportedOperationException();
    }

    @Override
    protected void revertUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode valueToRestore, ModelNode valueToRevert, Void handback) throws OperationFailedException {
        // should not be called as requiresRuntime returns false
        throw new UnsupportedOperationException();
    }
}
