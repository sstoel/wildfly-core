/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.requestcontroller;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jboss.as.server.suspend.ServerResumeContext;
import org.jboss.as.server.suspend.ServerSuspendContext;
import org.jboss.as.server.suspend.SuspendableActivity;
import org.jboss.as.server.suspend.SuspendableActivityRegistry;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.extension.requestcontroller.logging.RequestControllerLogger;

/**
 * A controller that manages the active requests that are running in the container.
 * <p/>
 * There are two main use cases for this:
 * <p/>
 * 1) Graceful shutdown - When the number of active request reaches zero then the container can be gracefully shut down
 * 2) Request limiting - This allows the total number of requests that are active to be limited.
 * <p/>
 *
 * @author Stuart Douglas
 */
public class RequestController implements Service<RequestController>, SuspendableActivity {

    static final ServiceName SERVICE_NAME = RequestControllerRootDefinition.REQUEST_CONTROLLER_CAPABILITY.getCapabilityServiceName();

    private static final AtomicIntegerFieldUpdater<RequestController> activeRequestCountUpdater = AtomicIntegerFieldUpdater.newUpdater(RequestController.class, "activeRequestCount");
    private static final AtomicReferenceFieldUpdater<RequestController, CompletableFuture> suspendUpdater = AtomicReferenceFieldUpdater.newUpdater(RequestController.class, CompletableFuture.class, "suspend");

    private volatile int maxRequestCount = -1;

    private volatile int activeRequestCount = 0;

    private volatile boolean paused = false;

    private final Map<ControlPointIdentifier, ControlPoint> entryPoints = new HashMap<>();

    @SuppressWarnings("unused")
    private volatile CompletableFuture<Void> suspend = null;

    private final boolean trackIndividualControlPoints;
    private final Supplier<SuspendableActivityRegistry> registry;

    public RequestController(boolean trackIndividualControlPoints, Supplier<SuspendableActivityRegistry> registry) {
        this.trackIndividualControlPoints = trackIndividualControlPoints;
        this.registry = registry;
    }

    private Timer timer;

    private final Deque<QueuedTask> taskQueue = new LinkedBlockingDeque<>();

    @Override
    public CompletionStage<Void> suspend(ServerSuspendContext context) {
        this.paused = true;
        CompletableFuture<Void> result = new CompletableFuture<>();
        suspendUpdater.set(this, result);

        if (activeRequestCountUpdater.get(this) == 0) {
            if (suspendUpdater.compareAndSet(this, result, null)) {
                result.complete(null);
            }
        }
        return result;
    }

    @Override
    public CompletionStage<Void> resume(ServerResumeContext context) {
        this.paused = false;
        CompletableFuture<Void> suspend = suspendUpdater.get(this);
        if (suspend != null) {
            suspendUpdater.compareAndSet(this, suspend, null);
            suspend.cancel(false);
        }
        while (!taskQueue.isEmpty() && (activeRequestCount < maxRequestCount || maxRequestCount < 0)) {
            runQueuedTask(false);
        }
        return SuspendableActivity.COMPLETED;
    }

    /**
     * Pauses control points matching the specified predicate.
     *
     * @param filter a control point filter
     * @return a stage that will complete when the deployments matching the specified predicate are paused.
     */
    private synchronized CompletionStage<Void> pause(Predicate<ControlPoint> filter) {
        List<ControlPoint> controlPoints = this.entryPoints.values().stream().filter(filter).collect(Collectors.toUnmodifiableList());
        if (controlPoints.isEmpty()) return SuspendableActivity.COMPLETED;
        AtomicInteger count = new AtomicInteger(controlPoints.size());
        CompletableFuture<Void> result = new CompletableFuture<>();
        for (ControlPoint controlPoint : controlPoints) {
            controlPoint.pause().whenComplete(new BiConsumer<>() {
                @Override
                public void accept(Void ignore, Throwable exception) {
                    if (exception != null) {
                        result.completeExceptionally(exception);
                    } else if (count.decrementAndGet() == 0) {
                        result.complete(null);
                    }
                }
            });
        }
        return result;
    }

    /**
     * Pauses a given deployment
     *
     * @param deployment The deployment to pause
     * @return a stage that completes when all control points for the the specified deployment have paused.
     */
    public CompletionStage<Void> pauseDeployment(final String deployment) {
        return this.pause(new DeploymentFilter(deployment));
    }

    /**
     * Pauses a given deployment
     *
     * @param deployment The deployment to pause
     * @param listener The listener that will be notified when the pause is complete
     * @deprecated Superseded by {@link #pauseDeployment(String)}.
     */
    @Deprecated(forRemoval = true, since = "26.0.0")
    public void pauseDeployment(final String deployment, org.jboss.as.server.suspend.ServerActivityCallback listener) {
        this.pauseDeployment(deployment).whenComplete((ignore, exception) -> listener.done());
    }

    /**
     * Resumes the control points matching the specified predicate.
     * @param filter a control point filter
     */
    private synchronized void resume(Predicate<ControlPoint> filter) {
        this.entryPoints.values().stream().filter(filter).forEach(ControlPoint::resume);
    }

    /**
     * resumed a given deployment
     *
     * @param deployment The deployment to resume
     */
    public void resumeDeployment(final String deployment) {
        this.resume(new DeploymentFilter(deployment));
    }

    /**
     * Pauses a given entry point. This can be used to stop all requests though a given mechanism, e.g. all web requests
     *
     * @param controlPoint the entry point to pause
     * @return a stage that completes when all control points for the specified entry point have paused.
     */
    public CompletionStage<Void> pauseControlPoint(final String entryPoint) {
        return this.pause(new EntryPointFilter(entryPoint));
    }

    /**
     * Pauses a given entry point. This can be used to stop all requests though a given mechanism, e.g. all web requests
     *
     * @param entryPoint the entry point to pause
     * @param listener   The listener
     * @deprecated Superseded by {@link #pauseControlPoint(String)}
     */
    @Deprecated(forRemoval = true)
    public void pauseControlPoint(final String entryPoint, org.jboss.as.server.suspend.ServerActivityCallback listener) {
        this.pauseControlPoint(entryPoint).whenComplete((ignore, exception) -> listener.done());
    }

    /**
     * Resumes a given entry point type;
     *
     * @param entryPoint The entry point
     */
    public void resumeControlPoint(final String entryPoint) {
        this.resume(new EntryPointFilter(entryPoint));
    }

    public synchronized RequestControllerState getState() {
        final List<RequestControllerState.EntryPointState> eps = new ArrayList<>();
        for (ControlPoint controlPoint : entryPoints.values()) {
            eps.add(new RequestControllerState.EntryPointState(controlPoint.getDeployment(), controlPoint.getEntryPoint(), controlPoint.isPaused(), controlPoint.getActiveRequestCount()));
        }
        return new RequestControllerState(paused, activeRequestCount, maxRequestCount, eps);
    }

    RunResult beginRequest(boolean force) {
        int maxRequests = maxRequestCount;
        int active = activeRequestCountUpdater.get(this);
        boolean success = false;
        while ((maxRequests <= 0 || active < maxRequests) && (!paused || force)) {
            if (activeRequestCountUpdater.compareAndSet(this, active, active + 1)) {
                success = true;
                break;
            }
            active = activeRequestCountUpdater.get(this);
        }
        if (success) {
            //re-check the paused state
            //this is necessary because there is a race between checking paused and updating active requests
            //if this happens we just call requestComplete(), as the listener can only be invoked once it does not
            //matter if it has already been invoked
            if(!force && paused) {
                requestComplete();
                return RunResult.REJECTED;
            }
            return RunResult.RUN;
        } else {
            return RunResult.REJECTED;
        }
    }

    void requestComplete() {
        runQueuedTask(true);
    }

    private void decrementRequestCount() {

        int result = activeRequestCountUpdater.decrementAndGet(this);
        if (paused) {
            if (paused && result == 0) {
                CompletableFuture<Void> suspend = suspendUpdater.get(this);
                if (suspend != null) {
                    if (suspendUpdater.compareAndSet(this, suspend, null)) {
                        suspend.complete(null);
                    }
                }
            }
        }
    }

    /**
     * Gets an entry point for the given deployment. If one does not exist it will be created. If the request controller is disabled
     * this will return null.
     *
     * Entry points are reference counted. If this method is called n times then {@link #removeControlPoint(ControlPoint)}
     * must also be called n times to clean up the entry points.
     *
     * @param deploymentName The top level deployment name
     * @param entryPointName The entry point name
     * @return The entry point, or null if the request controller is disabled
     */
    public synchronized ControlPoint getControlPoint(final String deploymentName, final String entryPointName) {
        ControlPointIdentifier id = new ControlPointIdentifier(deploymentName, entryPointName);
        ControlPoint ep = entryPoints.get(id);
        if (ep == null) {
            ep = new ControlPoint(this, deploymentName, entryPointName, trackIndividualControlPoints);
            entryPoints.put(id, ep);
        }
        ep.increaseReferenceCount();
        return ep;
    }

    /**
     * Removes the specified entry point
     *
     * @param controlPoint The entry point
     */
    public synchronized void removeControlPoint(ControlPoint controlPoint) {
        if (controlPoint.decreaseReferenceCount() == 0) {
            ControlPointIdentifier id = new ControlPointIdentifier(controlPoint.getDeployment(), controlPoint.getEntryPoint());
            entryPoints.remove(id);
        }
    }

    /**
     * @return The maximum number of requests that can be active at a time
     */
    public int getMaxRequestCount() {
        return maxRequestCount;
    }

    /**
     * Sets the maximum number of requests that can be active at a time.
     * <p/>
     * If this is higher that the number of currently running requests the no new requests
     * will be able to run until the number of active requests has dropped below this level.
     *
     * @param maxRequestCount The max request count
     */
    public void setMaxRequestCount(int maxRequestCount) {
        this.maxRequestCount = maxRequestCount;
        while (!taskQueue.isEmpty() && (activeRequestCount < maxRequestCount || maxRequestCount < 0)) {
            if(!runQueuedTask(false)) {
                break;
            }
        }
    }

    /**
     * @return <code>true</code> If the server is currently pause
     */
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        this.registry.get().registerActivity(this);
        timer = new Timer();
    }

    @Override
    public void stop(StopContext stopContext) {
        this.registry.get().registerActivity(this);
        timer.cancel();
        timer = null;
        while (!taskQueue.isEmpty()) {
            QueuedTask t = taskQueue.poll();
            if(t != null) {
                t.run();
            }
        }
    }

    @Override
    public RequestController getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public int getActiveRequestCount() {
        return activeRequestCount;
    }

    void queueTask(ControlPoint controlPoint, Runnable task, Executor taskExecutor, long timeout, Runnable timeoutTask, boolean rejectOnSuspend, boolean forceRun) {
        if(paused) {
            if(rejectOnSuspend && !forceRun) {
                taskExecutor.execute(timeoutTask);
                return;
            }
        }
        QueuedTask queuedTask = new QueuedTask(taskExecutor, task, timeoutTask, controlPoint, forceRun);
        taskQueue.add(queuedTask);
        runQueuedTask(false);
        if(queuedTask.isQueued()) {
            if(timeout > 0) {
                timer.schedule(queuedTask, timeout);
            }
        }
    }

    /**
     * Runs a queued task, if the queue is not already empty.
     *
     * Note that this will decrement the request count if there are no queued tasks to be run
     *
     * @param hasPermit If the caller has already called {@link #beginRequest(boolean force)}
     */
    private boolean runQueuedTask(boolean hasPermit) {
        if (!hasPermit && beginRequest(paused) == RunResult.REJECTED) {
            return false;
        }
        QueuedTask task = null;
        if (!paused) {
            task = taskQueue.poll();
        } else {
            //the container is suspended, but we still need to run any force queued tasks
            task = findForcedTask();
        }
        if (task != null) {
            if(!task.runRequest()) {
                decrementRequestCount();
            }
            return true;
        } else {
            decrementRequestCount();
            return false;
        }
    }

    private QueuedTask findForcedTask() {
        QueuedTask forcedTask = null;
        QueuedTask task;
        List<QueuedTask> storage = new ArrayList<>();
        while (forcedTask == null && (task = taskQueue.poll()) != null) {
            if (task.forceRun) {
                forcedTask = task;
            } else {
                storage.add(task);
            }
        }
        // this screws the order somewhat, but the container is suspending anyway, and the order
        // was never guarenteed. if we push them back onto the front we will need to just go through them again
        taskQueue.addAll(storage);
        return forcedTask;
    }

    private static final class ControlPointIdentifier {
        private final String deployment, name;

        private ControlPointIdentifier(String deployment, String name) {
            this.deployment = deployment;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ControlPointIdentifier that = (ControlPointIdentifier) o;

            if (deployment != null ? !deployment.equals(that.deployment) : that.deployment != null) return false;
            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = deployment != null ? deployment.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }


    private static final class QueuedTask extends TimerTask {

        private final Executor executor;
        private final Runnable task;
        private final Runnable cancelTask;
        private final ControlPoint controlPoint;
        private final boolean forceRun;

        //0 == queued
        //1 == run
        //2 == cancelled
        private final AtomicInteger state = new AtomicInteger(0);

        private QueuedTask(Executor executor, Runnable task, Runnable cancelTask, ControlPoint controlPoint, boolean forceRun) {
            this.executor = executor;
            this.task = task;
            this.cancelTask = cancelTask;
            this.controlPoint = controlPoint;
            this.forceRun = forceRun;
        }

        @Override
        public void run() {
            if(state.compareAndSet(0, 2)) {
                if(cancelTask != null) {
                    try {
                        executor.execute(cancelTask);
                    } catch (Exception e) {
                        //should only happen if the server is shutting down
                        RequestControllerLogger.ROOT_LOGGER.failedToCancelTask(cancelTask, e);
                    }
                }
            }
        }

        public boolean runRequest() {
            if (state.compareAndSet(0, 1)) {
                cancel();
                executor.execute(new ControlPointTask(task, controlPoint));
                return true;
            } else {
                return false;
            }
        }

        boolean isQueued() {
            return state.get() == 0;
        }
    }

    private static class DeploymentFilter implements Predicate<ControlPoint> {
        private final String deployment;

        DeploymentFilter(String deployment) {
            this.deployment = deployment;
        }

        @Override
        public boolean test(ControlPoint controlPoint) {
            return controlPoint.getDeployment().equals(this.deployment);
        }
    }

    private static class EntryPointFilter implements Predicate<ControlPoint> {
        private final String entryPoint;

        EntryPointFilter(String entryPoint) {
            this.entryPoint = entryPoint;
        }

        @Override
        public boolean test(ControlPoint controlPoint) {
            return controlPoint.getEntryPoint().equals(this.entryPoint);
        }
    }
}
