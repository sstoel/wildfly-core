/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.service;

import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.wildfly.common.function.Functions;

/**
 * Encapsulates service installation into a {@link ServiceTarget}.
 * @author Paul Ferraro
 */
public interface ServiceInstaller extends Installer<ServiceTarget> {

    /**
     * Returns a {@link ServiceInstaller} builder whose installed service provides the specified value.
     * @param <V> the service value type
     * @param value the service value
     * @return a service installer builder
     */
    static <V> UnaryBuilder<V, V> builder(V value) {
        return builder(Functions.constantSupplier(value)).asActive();
    }

    /**
     * Returns a {@link ServiceInstaller} builder whose installed service provides the value supplied by the specified dependency.
     * @param <V> the service value type
     * @param dependency a service dependency
     * @return a service installer builder
     */
    static <V> UnaryBuilder<V, V> builder(ServiceDependency<V> dependency) {
        Supplier<V> supplier = dependency;
        return builder(supplier).requires(dependency).asPassive();
    }

    /**
     * Returns a {@link ServiceInstaller} builder whose installed service provides the value supplied by the specified factory.
     * @param <V> the service value type
     * @param factory provides the service value
     * @return a service installer builder
     */
    static <V> UnaryBuilder<V, V> builder(Supplier<V> factory) {
        return builder(Function.identity(), factory);
    }

    /**
     * Returns a {@link ServiceInstaller} builder whose installed service provides the value supplied by the specified factory and mapping function.
     * @param <T> the source value type
     * @param <V> the service value type
     * @param mapper a function that returns the service value given the value supplied by the factory
     * @param factory provides the input to the specified mapper
     * @return a service installer builder
     */
    static <T, V> UnaryBuilder<T, V> builder(Function<T, V> mapper, Supplier<T> factory) {
        return new DefaultUnaryBuilder<>(mapper, factory);
    }

    /**
     * Returns a {@link ServiceInstaller} builder that installs the specified installer into a child target.
     * @param installer a service installer
     * @return a service installer builder
     */
    static Builder builder(ServiceInstaller installer) {
        return new DefaultNullaryBuilder(new Service() {
            @Override
            public void start(StartContext context) {
                installer.install(context.getChildTarget());
            }

            @Override
            public void stop(StopContext context) {
                // Services installed into child target are auto-removed after this service stops.
            }
        }).asActive();
    }

    /**
     * Returns a {@link ServiceInstaller} builder that executes the specified tasks on {@link Service#start(StartContext)} and {@link Service#stop(StopContext)}, respectively.
     * @param startTask a start task
     * @param stopTask a stop task
     * @return a service installer builder
     */
    static Builder builder(Runnable startTask, Runnable stopTask) {
        return new DefaultNullaryBuilder(new Service() {
            @Override
            public void start(StartContext context) {
                startTask.run();
            }

            @Override
            public void stop(StopContext context) {
                stopTask.run();
            }
        });
    }

    /**
     * Implemented by builds with asynchronous service support.
     * @param <B> the builder type
     */
    interface AsyncBuilder<B> {
        /**
         * Indicates that the installed service should start and, if a stop task was specified, stop asynchronously.
         * @param executor supplies the executor used for asynchronous execution
         * @return a reference to this builder
         */
        B async(Supplier<Executor> executor);
    }

    /**
     * Builds a {@link ServiceInstaller}.
     * @param <T> the source value type
     * @param <V> the service value type
     */
    interface Builder extends AsyncBuilder<Builder>, Installer.Builder<Builder, ServiceInstaller, ServiceTarget, ServiceBuilder<?>> {
    }

    /**
     * Builds a {@link ServiceInstaller} whose service provides a single value.
     * @param <T> the source value type
     * @param <V> the service value type
     */
    interface UnaryBuilder<T, V> extends AsyncBuilder<UnaryBuilder<T, V>>, Installer.UnaryBuilder<UnaryBuilder<T, V>, ServiceInstaller, ServiceTarget, ServiceBuilder<?>, T, V> {
    }

    class DefaultServiceInstaller extends DefaultInstaller<ServiceTarget, ServiceBuilder<?>, ServiceBuilder<?>> implements ServiceInstaller {

        DefaultServiceInstaller(Configuration<ServiceBuilder<?>, ServiceBuilder<?>> config, Function<ServiceTarget, ServiceBuilder<?>> serviceBuilderFactory) {
            super(config, serviceBuilderFactory);
        }
    }

    class DefaultNullaryBuilder extends AbstractNullaryBuilder<Builder, ServiceInstaller, ServiceTarget, ServiceBuilder<?>, ServiceBuilder<?>> implements Builder {
        private volatile Supplier<Executor> executor = null;

        DefaultNullaryBuilder(Service service) {
            super(service);
        }

        @Override
        public Builder async(Supplier<Executor> executor) {
            this.executor = executor;
            return this;
        }

        @Override
        public ServiceInstaller build() {
            Supplier<Executor> executor = this.executor;
            return new DefaultServiceInstaller(this, new Function<>() {
                @Override
                public ServiceBuilder<?> apply(ServiceTarget target) {
                    ServiceBuilder<?> builder = target.addService();
                    return (executor != null) ? new AsyncServiceBuilder<>(builder, executor, AsyncServiceBuilder.Async.START_AND_STOP) : builder;
                }
            });
        }

        @Override
        protected Builder builder() {
            return this;
        }
    }

    class DefaultUnaryBuilder<T, V> extends AbstractUnaryBuilder<ServiceInstaller.UnaryBuilder<T, V>, ServiceInstaller, ServiceTarget, ServiceBuilder<?>, ServiceBuilder<?>, T, V> implements ServiceInstaller.UnaryBuilder<T, V> {
        private volatile Supplier<Executor> executor = null;

        DefaultUnaryBuilder(Function<T, V> mapper, Supplier<T> factory) {
            super(mapper, factory);
        }

        @Override
        public ServiceInstaller.UnaryBuilder<T, V> async(Supplier<Executor> executor) {
            this.executor = executor;
            return this;
        }

        @Override
        public ServiceInstaller build() {
            Supplier<Executor> executor = this.executor;
            // If no stop task is specified, we can stop synchronously
            AsyncServiceBuilder.Async async = this.hasStopTask() ? AsyncServiceBuilder.Async.START_AND_STOP : AsyncServiceBuilder.Async.START_ONLY;
            return new DefaultServiceInstaller(this, new Function<>() {
                @Override
                public ServiceBuilder<?> apply(ServiceTarget target) {
                    ServiceBuilder<?> builder = target.addService();
                    return (executor != null) ? new AsyncServiceBuilder<>(builder, executor, async) : builder;
                }
            });
        }

        @Override
        protected UnaryBuilder<T, V> builder() {
            return this;
        }
    }
}
