/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.subsystem;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.jboss.as.controller.Feature;
import org.jboss.as.controller.FeatureRegistry;
import org.jboss.as.controller.PersistentResourceXMLDescription;
import org.jboss.as.controller.PersistentResourceXMLDescriptionReader;
import org.jboss.as.controller.PersistentResourceXMLDescriptionWriter;
import org.jboss.as.controller.PersistentSubsystemSchema;
import org.jboss.as.controller.SubsystemSchema;
import org.jboss.as.controller.persistence.SubsystemMarshallingContext;
import org.jboss.as.version.Stability;
import org.jboss.dmr.ModelNode;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLElementWriter;
import org.wildfly.common.Assert;

/**
 * Encapsulates the persistence configuration of a subsystem.
 * @author Paul Ferraro
 * @param <S> the schema type
 */
public interface SubsystemPersistence<S extends SubsystemSchema<S>> {

    /**
     * Returns the supported schemas of this subsystem.
     * @return a set of schemas
     */
    Set<S> getSchemas();

    /**
     * Returns the appropriate StAX reader for the version of the specified schema that parses subsystem XML into a set of management operations.
     * @return a StAX reader for the version of the specified schema
     */
    default XMLElementReader<List<ModelNode>> getReader(S schema) {
        return schema;
    }

    /**
     * Returns a StAX writer that writes the subsystem model to XML according to the current schema version.
     * @return a StAX writer
     * @deprecated Replaced by {@link #getWriter(FeatureRegistry)}
     */
    @Deprecated(forRemoval = true)
    default XMLElementWriter<SubsystemMarshallingContext> getWriter() {
        return this.getWriter(Stability.DEFAULT);
    }

    /**
     * Returns a StAX writer that writes the subsystem model to XML according to the current schema version for the specified stability level.
     * @param stability the stability level of the process
     * @return a StAX writer
     */
    XMLElementWriter<SubsystemMarshallingContext> getWriter(Stability stability);

    /**
     * Creates the subsystem persistence configuration for the current version of the schema.
     * @param <S> the schema type
     * @param currentSchema the current schema version
     * @return a subsystem persistence configuration
     */
    static <S extends Enum<S> & PersistentSubsystemSchema<S>> SubsystemPersistence<S> of(S currentSchema) {
        return of(EnumSet.of(currentSchema));
    }

    /**
     * Creates the subsystem persistence configuration for the current versions of the schema.
     * @param <S> the schema type
     * @param currentSchemas the current schema versions
     * @return a subsystem persistence configuration
     */
    static <S extends Enum<S> & PersistentSubsystemSchema<S>> SubsystemPersistence<S> of(Set<S> currentSchemas) {
        Assert.assertFalse(currentSchemas.isEmpty());
        Class<S> schemaClass = currentSchemas.iterator().next().getDeclaringClass();
        // Build PersistentResourceXMLDescription for current schemas to share between reader and writer.
        Map<S, PersistentResourceXMLDescription> currentXMLDescriptions = new EnumMap<>(schemaClass);
        for (S currentSchema : currentSchemas) {
            currentXMLDescriptions.put(currentSchema, currentSchema.getXMLDescription());
        }
        Map<Stability, S> currentSchemaPerStability = Feature.map(currentSchemas);
        return new SubsystemPersistence<>() {
            @Override
            public Set<S> getSchemas() {
                return EnumSet.allOf(schemaClass);
            }

            @Override
            public XMLElementReader<List<ModelNode>> getReader(S schema) {
                return Optional.ofNullable(currentXMLDescriptions.get(schema)).<XMLElementReader<List<ModelNode>>>map(PersistentResourceXMLDescriptionReader::new).orElse(schema);
            }

            @Override
            public XMLElementWriter<SubsystemMarshallingContext> getWriter(Stability stability) {
                S currentSchema = currentSchemaPerStability.get(stability);
                return new PersistentResourceXMLDescriptionWriter(currentXMLDescriptions.get(currentSchema));
            }
        };
    }

    /**
     * Creates the subsystem persistence configuration for the current version of the schema and specified writer
     * @param <S> the schema type
     * @param currentSchema the current schema version
     * @return a subsystem persistence configuration
     */
    static <S extends Enum<S> & SubsystemSchema<S>> SubsystemPersistence<S> of(S currentSchema, XMLElementWriter<SubsystemMarshallingContext> writer) {
        return of(Map.of(currentSchema, writer));
    }

    /**
     * Creates the subsystem persistence configuration for the specified writers for current versions of the schema.
     * @param <S> the schema type
     * @param currentWirter the schema writer per current schema version
     * @return a subsystem persistence configuration
     */
    static <S extends Enum<S> & SubsystemSchema<S>> SubsystemPersistence<S> of(Map<S, XMLElementWriter<SubsystemMarshallingContext>> currentWriters) {
        Assert.assertFalse(currentWriters.isEmpty());
        Class<S> schemaClass = currentWriters.keySet().iterator().next().getDeclaringClass();
        return new SubsystemPersistence<>() {
            @Override
            public Set<S> getSchemas() {
                return EnumSet.allOf(schemaClass);
            }

            @Override
            public XMLElementWriter<SubsystemMarshallingContext> getWriter(Stability stability) {
                return currentWriters.get(stability);
            }
        };
    }

    /**
     * Creates the subsystem persistence configuration for the specified set of schemas, reader factory, and writer.
     * @param <S> the schema type
     * @param <R> the schema reader type
     * @param schemas a set of schemas
     * @param readerFactory a factory for creating an XML reader for a specific schema
     * @param writer an XML reader
     * @return a subsystem persistence configuration
     * @deprecated Use {@link #of(Enum, XMLElementWriter) instead
     */
    @Deprecated(forRemoval = true)
    static <S extends SubsystemSchema<S>, R extends XMLElementReader<List<ModelNode>>> SubsystemPersistence<S> of(Set<S> schemas, Function<S, R> readerFactory, XMLElementWriter<SubsystemMarshallingContext> writer) {
        return new DefaultSubsystemPersistence<>(schemas, readerFactory, writer);
    }

    /**
     * Creates the subsystem persistence configuration for the current version of the schema and specified writer
     * @param <S> the schema type
     * @param <R> the schema reader type
     * @param schemas a set of schemas
     * @param readerFactory a factory for creating an XML reader for a specific schema
     * @param currentSchema the current schema version
     * @param currentReader the XML reader for the current schema version
     * @param writer an XML reader
     * @return a subsystem persistence configuration
     * @deprecated Use {@link #of(Enum, XMLElementWriter) instead
     */
    @Deprecated(forRemoval = true)
    static <S extends SubsystemSchema<S>, R extends XMLElementReader<List<ModelNode>>> SubsystemPersistence<S> of(Set<S> schemas, Function<S, R> readerFactory, S currentSchema, XMLElementReader<List<ModelNode>> currentReader, XMLElementWriter<SubsystemMarshallingContext> writer) {
        return new DefaultSubsystemPersistence<>(schemas, readerFactory, writer) {
            @Override
            public XMLElementReader<List<ModelNode>> getReader(S schema) {
                return (schema == currentSchema) ? currentReader : readerFactory.apply(schema);
            }
        };
    }

    @Deprecated(forRemoval = true)
    class DefaultSubsystemPersistence<S extends SubsystemSchema<S>, R extends XMLElementReader<List<ModelNode>>> implements SubsystemPersistence<S> {
        private final Set<S> schemas;
        private final Function<S, R> readerFactory;
        private final XMLElementWriter<SubsystemMarshallingContext> writer;

        DefaultSubsystemPersistence(Set<S> schemas, Function<S, R> readerFactory, XMLElementWriter<SubsystemMarshallingContext> writer) {
            this.schemas = schemas;
            this.readerFactory = readerFactory;
            this.writer = writer;
        }

        @Override
        public Set<S> getSchemas() {
            return this.schemas;
        }

        @Override
        public XMLElementReader<List<ModelNode>> getReader(S schema) {
            return this.readerFactory.apply(schema);
        }

        @Override
        public XMLElementWriter<SubsystemMarshallingContext> getWriter(Stability stability) {
            return this.writer;
        }
    }
}
