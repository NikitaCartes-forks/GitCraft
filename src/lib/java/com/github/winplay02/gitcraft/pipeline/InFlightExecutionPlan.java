package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.graph.AbstractVersion;
import com.github.winplay02.gitcraft.graph.AbstractVersionGraph;
import com.github.winplay02.gitcraft.util.CachedHashKeyWrapper;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public record InFlightExecutionPlan<T extends AbstractVersion<T>, C extends IStepContext<C, T>, D extends IStepConfig>(
																			PipelineExecutionGraph<T, C, D> executionGraph,
																			Set<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>> completedSubset,
																			Set<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>> executingSubset,
																			Set<IStep<T, ?, C, D>> activeSteps,
																			Map<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, Exception> failedTasks,
																			Map<T, C> versionedContexts,
																			Map<T, D> versionedConfigs,
																			Map<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, Set<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>>> dependents,
																			Map<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, AtomicInteger> remainingDeps,
																			Set<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>> deferredTasks,
																			Object executionLock,
																			Object conditionalVar) {

	public static <T extends AbstractVersion<T>, C extends IStepContext<C, T>, D extends IStepConfig> InFlightExecutionPlan<T, C, D> create(PipelineDescription<T, C, D> description, AbstractVersionGraph<T> versionGraph) {
		PipelineExecutionGraph<T, C, D> executionGraph = PipelineExecutionGraph.populate(description, versionGraph);
		// Build reverse edges (dependents) and in-degree counters once
		Map<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, Set<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>>> dependents = new HashMap<>();
		Map<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, AtomicInteger> remainingDeps = new HashMap<>();
		for (Map.Entry<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, Set<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>>> entry : executionGraph.stepVersionSubsetEdges().entrySet()) {
			remainingDeps.put(entry.getKey(), new AtomicInteger(entry.getValue().size()));
			for (CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> dependency : entry.getValue()) {
				dependents.computeIfAbsent(dependency, __ -> new HashSet<>()).add(entry.getKey());
			}
		}
		return new InFlightExecutionPlan<>(executionGraph, ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), dependents, remainingDeps, ConcurrentHashMap.newKeySet(), new Object(), new Object());
	}

	/**
	 * Marks every task of a skipped version as completed before execution starts, so no executor slot is spent on them.
	 * The dependency counters are decremented as usual, thus tasks that only wait on skipped tasks are ready afterwards.
	 */
	private void reduce(ExecutorService executor, IPipeline<T, C, D> pipeline, RepoWrapper repository, AbstractVersionGraph<T> versionGraph) {
		// Cache access to repository
		if (repository != null) {
			repository.changeGitLogCacheBehavior(true);
		}
		Deque<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>> ready = new ArrayDeque<>();
		for (Map.Entry<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, AtomicInteger> entry : this.remainingDeps.entrySet()) {
			if (entry.getValue().get() == 0) {
				ready.add(entry.getKey());
			}
		}
		int skipped = 0;
		while (!ready.isEmpty()) {
			CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> task = ready.poll();
			C context = this.versionedContexts().computeIfAbsent(task.inner().version(), ctxVersion -> pipeline.getDescription().contextCreator().getContext(ctxVersion, repository, versionGraph, executor));
			if (!pipeline.getDescription().skipVersion().apply(versionGraph, context)) {
				continue;
			}
			this.completedSubset.add(task);
			++skipped;
			for (CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> dependent : this.dependents.getOrDefault(task, Set.of())) {
				if (this.remainingDeps.get(dependent).decrementAndGet() == 0) {
					ready.add(dependent);
				}
			}
		}
		if (repository != null) {
			repository.changeGitLogCacheBehavior(false);
		}
		MiscHelper.println("Skipping %s steps!", skipped);
	}

	private void runSingleTask(ExecutorService executor, CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> task, IPipeline<T, C, D> pipeline, RepoWrapper repository, AbstractVersionGraph<T> versionGraph) {
		if (executor.isShutdown()) {
			return;
		}

		executor.execute(() -> {
			synchronized (executionLock) {
				if (executingSubset.contains(task) || completedSubset.contains(task)) {
					return;
				}
				if (task.inner().step().getParallelismPolicy().isRestrictedToSequential() && activeSteps.contains(task.inner().step())) {
					// slot busy; remember to retry once the step's active task completes
					deferredTasks.add(task);
					return;
				}

				deferredTasks.remove(task);
				activeSteps.add(task.inner().step());
				executingSubset.add(task);
			}

			if (pipeline.threadLimiter() != null) {
				pipeline.threadLimiter().acquireUninterruptibly();
			}

			C context = this.versionedContexts().computeIfAbsent(task.inner().version(), ctxVersion -> pipeline.getDescription().contextCreator().getContext(ctxVersion, repository, versionGraph, executor));
			D config = this.versionedConfigs().computeIfAbsent(task.inner().version(), pipeline.getDescription().configCreator());
			Exception storedException = null;

			try {
				if (!pipeline.getDescription().skipVersion().apply(versionGraph, context)) {
					pipeline.runSingleVersionSingleStep(task.inner(), context, config);
				} else {
					MiscHelper.println("Skipping step '%s' for %s (%s)...", task.inner().step().getName(), context, config);
				}
				executingSubset.remove(task);
				completedSubset.add(task);
				activeSteps.remove(task.inner().step());
			} catch (Exception e) {
				storedException = e;
				MiscHelper.println("Step '%s' for %s (%s) failed: %s", task.inner().step().getName(), context, config, e);
				e.printStackTrace();
			}
			if (pipeline.threadLimiter() != null) {
				pipeline.threadLimiter().release();
			}

			synchronized (executionLock) {
				if (storedException == null) {
					// success :)
					executingSubset.remove(task);
					activeSteps.remove(task.inner().step());
					completedSubset.add(task);
				} else {
					// failure :(
					failedTasks.put(task, storedException);
				}

				signalUpdate();

				if (storedException == null) {
					onTaskCompleted(executor, task, pipeline, repository, versionGraph);
				} else {
					executor.shutdown();
				}
			}
		});
	}

	private void onTaskCompleted(ExecutorService executor, CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> completedTask, IPipeline<T, C, D> pipeline, RepoWrapper repository, AbstractVersionGraph<T> versionGraph) {
		// Unblock direct dependents; schedule the ones whose last dependency just completed
		for (CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> dependent : this.dependents.getOrDefault(completedTask, Set.of())) {
			if (this.remainingDeps.get(dependent).decrementAndGet() == 0) {
				this.runSingleTask(executor, dependent, pipeline, repository, versionGraph);
			}
		}
		// A sequential step slot may have freed up: retry tasks of the same step that were deferred
		if (completedTask.inner().step().getParallelismPolicy().isRestrictedToSequential() && !this.deferredTasks.isEmpty()) {
			for (CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>> deferred : this.deferredTasks) {
				if (deferred.inner().step().equals(completedTask.inner().step())) {
					this.runSingleTask(executor, deferred, pipeline, repository, versionGraph);
				}
			}
		}
	}

	public void run(ExecutorService executor, IPipeline<T, C, D> pipeline, RepoWrapper repository, AbstractVersionGraph<T> versionGraph) {
		reduce(executor, pipeline, repository, versionGraph);
		// Schedule all tasks that have no dependencies to begin with
		for (Map.Entry<CachedHashKeyWrapper<IPipeline.TupleVersionStep<T, C, D>>, AtomicInteger> entry : this.remainingDeps.entrySet()) {
			if (entry.getValue().get() == 0 && !this.completedSubset.contains(entry.getKey())) {
				this.runSingleTask(executor, entry.getKey(), pipeline, repository, versionGraph);
			}
		}
		await();
	}

	public int runningTasks() { // doesn't need to be absolutely accurate, when called concurrently; is intended to display some (approximate) information on the screen
		return this.executingSubset.size();
	}

	private void signalUpdate() {
		synchronized (conditionalVar) {
			conditionalVar.notifyAll();
		}
	}

	private void await() {
		while (true) {
			synchronized (executionLock) {
				// Once everything is completed
				if (this.completedSubset().size() == this.executionGraph().stepVersionSubsetVertices().size()) {
					return;
				}
				// If anything failed, report
				if (!this.failedTasks().isEmpty()) {
					MiscHelper.println("Execution failed, waiting for existing tasks to complete...");
					return;
				}
			}
			synchronized (conditionalVar) {
				// timed: the last update can be signalled between the check above and this wait
				try {
					conditionalVar.wait(1000);
				} catch (InterruptedException ignored) {}
			}
		}
	}
}
