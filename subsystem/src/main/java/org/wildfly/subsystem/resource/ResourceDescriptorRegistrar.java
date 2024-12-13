/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.subsystem.resource;

import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.descriptions.DefaultResourceAddDescriptionProvider;
import org.jboss.as.controller.descriptions.DefaultResourceRemoveDescriptionProvider;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.operations.global.ListOperations;
import org.jboss.as.controller.operations.global.MapOperations;
import org.jboss.as.controller.operations.global.UndefineAttributeHandler;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.wildfly.subsystem.resource.operation.AddResourceOperationStepHandler;
import org.wildfly.subsystem.resource.operation.ReadAttributeTranslationOperationStepHandler;
import org.wildfly.subsystem.resource.operation.RemoveResourceOperationStepHandler;
import org.wildfly.subsystem.resource.operation.WriteAttributeTranslationOperationStepHandler;

/**
 * Registers add, remove, and write-attribute operation handlers and capabilities.
 * @author Paul Ferraro
 */
public class ResourceDescriptorRegistrar implements ManagementResourceRegistrar {

    public enum RestartMode {
        RESTART_SERVICES(OperationEntry.Flag.RESTART_NONE, OperationEntry.Flag.RESTART_RESOURCE_SERVICES),
        RELOAD_REQUIRED(OperationEntry.Flag.RESTART_ALL_SERVICES, OperationEntry.Flag.RESTART_ALL_SERVICES),
        RESTART_REQUIRED(OperationEntry.Flag.RESTART_JVM, OperationEntry.Flag.RESTART_JVM),
        ;
        private final OperationEntry.Flag addOperationFlag;
        private final OperationEntry.Flag removeOperationFlag;

        RestartMode(OperationEntry.Flag addOperationFlag, OperationEntry.Flag removeOperationFlag) {
            this.addOperationFlag = addOperationFlag;
            this.removeOperationFlag = removeOperationFlag;
        }

        OperationEntry.Flag getAddOperationFlag() {
            return this.addOperationFlag;
        }

        OperationEntry.Flag getRemoveOperationFlag() {
            return this.removeOperationFlag;
        }
    }

    private static final Map<OperationDefinition, OperationStepHandler> GLOBAL_OPERATIONS = Map.ofEntries(
            Map.entry(UndefineAttributeHandler.DEFINITION, UndefineAttributeHandler.INSTANCE),
            Map.entry(MapOperations.MAP_PUT_DEFINITION, MapOperations.MAP_PUT_HANDLER),
            Map.entry(MapOperations.MAP_GET_DEFINITION, MapOperations.MAP_GET_HANDLER),
            Map.entry(MapOperations.MAP_REMOVE_DEFINITION, MapOperations.MAP_REMOVE_HANDLER),
            Map.entry(MapOperations.MAP_CLEAR_DEFINITION, MapOperations.MAP_CLEAR_HANDLER),
            Map.entry(ListOperations.LIST_ADD_DEFINITION, ListOperations.LIST_ADD_HANDLER),
            Map.entry(ListOperations.LIST_GET_DEFINITION, ListOperations.LIST_GET_HANDLER),
            Map.entry(ListOperations.LIST_REMOVE_DEFINITION, ListOperations.LIST_REMOVE_HANDLER),
            Map.entry(ListOperations.LIST_CLEAR_DEFINITION, ListOperations.LIST_CLEAR_HANDLER)
    );

    private final ResourceDescriptor descriptor;

    ResourceDescriptorRegistrar(ResourceDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public void register(ManagementResourceRegistration registration) {
        // Registration might be null if this resource is not enabled by the current stability level
        if (registration != null) {
            for (RuntimeCapability<?> capability : this.descriptor.getCapabilities()) {
                registration.registerCapability(capability);
            }

            registration.registerRequirements(this.descriptor.getResourceCapabilityReferences());

            // Register attributes before operations
            for (AttributeDefinition attribute : this.descriptor.getAttributes()) {
                AttributeTranslation translation = this.descriptor.getAttributeTranslation(attribute);
                if (translation != null) {
                    registration.registerReadWriteAttribute(attribute, new ReadAttributeTranslationOperationStepHandler(translation), new WriteAttributeTranslationOperationStepHandler(translation));
                } else {
                    OperationStepHandler handler = this.descriptor.getWriteAttributeOperationStepHandler(attribute);
                    if (handler != null) {
                        registration.registerReadWriteAttribute(attribute, null, handler);
                    } else {
                        registration.registerReadOnlyAttribute(attribute, null);
                    }
                }
            }

            if (!registration.isRuntimeOnly()) {
                // Register add resource operation handler
                boolean ordered = registration.isOrderedChildResource();
                Stream<AttributeDefinition> attributes = registration.getAttributes(PathAddress.EMPTY_ADDRESS).values().stream()
                        .filter(AttributeAccess.Storage.CONFIGURATION) // Ignore runtime attributes
                        .map(AttributeAccess::getAttributeDefinition)
                        .filter(Predicate.not(AttributeDefinition::isResourceOnly)) // Ignore resource-only attributes
                        ;
                if (ordered) {
                    attributes = Stream.concat(Stream.of(DefaultResourceAddDescriptionProvider.INDEX), attributes);
                }
                OperationDefinition addDefinition = new SimpleOperationDefinitionBuilder(ModelDescriptionConstants.ADD, this.descriptor.getResourceDescriptionResolver())
                        .setParameters(attributes.toArray(AttributeDefinition[]::new))
                        .setDescriptionProvider(new DefaultResourceAddDescriptionProvider(registration, this.descriptor.getResourceDescriptionResolver(), ordered))
                        .setStability(registration.getStability())
                        .withFlag(this.descriptor.getAddOperationRestartFlag())
                        .build();
                registration.registerOperationHandler(addDefinition, this.descriptor.getOperationTransformation(ModelDescriptionConstants.ADD).apply(new AddResourceOperationStepHandler(this.descriptor)));

                // Register remove resource operation handler
                OperationDefinition removeDefinition = new SimpleOperationDefinitionBuilder(ModelDescriptionConstants.REMOVE, this.descriptor.getResourceDescriptionResolver())
                        .setDescriptionProvider(new DefaultResourceRemoveDescriptionProvider(this.descriptor.getResourceDescriptionResolver()))
                        .setStability(registration.getStability())
                        .withFlag(this.descriptor.getRemoveOperationRestartFlag())
                        .build();
                registration.registerOperationHandler(removeDefinition, this.descriptor.getOperationTransformation(ModelDescriptionConstants.REMOVE).apply(new RemoveResourceOperationStepHandler(this.descriptor)));
            }

            // Override global operations with transformed operations, if necessary
            for (Map.Entry<OperationDefinition, OperationStepHandler> entry : GLOBAL_OPERATIONS.entrySet()) {
                OperationDefinition definition = entry.getKey();
                OperationStepHandler handler = entry.getValue();
                // Only override global operation handlers for non-identity transformations
                OperationStepHandler transformedHandler = this.descriptor.getOperationTransformation(definition.getName()).apply(handler);
                if (handler != transformedHandler) {
                    registration.registerOperationHandler(definition, transformedHandler);
                }
            }
        }
    }
}
