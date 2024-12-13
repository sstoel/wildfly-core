/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.server.suspend;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A server activity that may have to finish before the server can shut down gracefully.
 *
 * @author Stuart Douglas
 * @deprecated Use {@link SuspendableActivity} instead.
 */
@Deprecated(forRemoval = true, since = "26.0.0")
public interface ServerActivity extends SuspendableActivity {

    /**
     * The lowest valid value to return from {@link #getExecutionGroup()}.
     */
    @SuppressWarnings("unused")
    int LOWEST_EXECUTION_GROUP = SuspendableActivityRegistry.SuspendPriority.FIRST.ordinal();
    /**
     * The default value returned from {@link #getExecutionGroup()}. Implementations should use this
     * unless there is a clear reason to use a different value.
     */
    int DEFAULT_EXECUTION_GROUP = SuspendableActivityRegistry.SuspendPriority.DEFAULT.ordinal();
    /**
     * The highest valid value to return from {@link #getExecutionGroup()}.
     */
    @SuppressWarnings("unused")
    int HIGHEST_EXECUTION_GROUP = SuspendableActivityRegistry.SuspendPriority.LAST.ordinal();

    /**
     * Returns a value that indicates to which set of {@code ServerActivity} instances
     * {@link ServerActivityRegistry#addServerActivity(ServerActivity) registered} with the {@link ServerActivityRegistry}
     * this activity should belong. All {@code ServerActivity} instances with the same execution group value have their
     * {@link #preSuspend(ServerActivityCallback) preSuspend}, {@link #suspended(ServerActivityCallback) suspended}
     * and {@link #resume() resume} methods invoked separately from activities with different execution group values.
     * <p>
     * The order in which execution groups will be processed depends on the method being invoked:
     * <ul>
     *     <li>For {@code preSuspend} and {@code suspended}, groups with a lower value are processed before those
     *     with a higher value.</li>
     *     <li>For {@code resume}, groups with a higher value are processed before those with a lower value.</li>
     * </ul>
     * <p>
     * There is no guarantee of any ordering of method invocation between activities in the same execution group,
     * and they may even be processed concurrently.
     * <p>
     * Note that {@code preSuspend} is invoked for all activity instances before the overall suspend process proceeds
     * to calls to {@code suspended}. The unit of grouping is the individual method invocations, not the overall
     * preSuspend/suspended process.
     * <p>
     * The default implementation of this method returns {@link #DEFAULT_EXECUTION_GROUP}.
     *
     * @return a value between {@link #LOWEST_EXECUTION_GROUP} and {@link #HIGHEST_EXECUTION_GROUP}, inclusive.
     */
    default int getExecutionGroup() {
        return DEFAULT_EXECUTION_GROUP;
    }

    /**
     * Invoked before the server is paused. This is the place where pause notifications should
     * be sent to external systems such as load balancers to tell them this node is about to go away.
     *
     * @param listener The listener to invoker when the pre-pause phase is done
     */
    void preSuspend(ServerActivityCallback listener);

    /**
     * Invoked once the suspend process has started. One this has been invoked
     * no new requests should be allowed to proceeed
     * @param listener The listener to invoke when suspend is done.
     */
    void suspended(ServerActivityCallback listener);

    /**
     * Invoked if the suspend or pre-suspend is cancelled or if a suspended server
     * is resumed.
     */
    void resume();

    @Override
    default CompletionStage<Void> prepare(ServerSuspendContext context) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            this.preSuspend(() -> future.complete(null));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    default CompletionStage<Void> suspend(ServerSuspendContext context) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            this.suspended(() -> future.complete(null));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    default CompletionStage<Void> resume(ServerResumeContext context) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            this.resume();
            future.complete(null);
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
