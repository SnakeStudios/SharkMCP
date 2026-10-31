package net.snakestudios.sharkmcp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One action at a time. {@link #stop()} drops whatever is waiting and clears
 * the cancel flag once the queue is empty, so the next action still runs.
 */
public final class ActionQueue implements AutoCloseable {
	private final Object lock = new Object();
	private final ArrayDeque<Job> jobs = new ArrayDeque<>();
	private final Thread worker;
	private volatile boolean cancel;
	private volatile boolean closed;

	public ActionQueue() {
		worker = new Thread(this::run, "sharkmcp-actions");
		worker.setDaemon(true);
		worker.start();
	}

	public String enqueue(Supplier<String> work) throws Exception {
		return submit(work).get(90, TimeUnit.SECONDS);
	}

	public <T> T enqueueValue(Supplier<T> work) throws Exception {
		CompletableFuture<T> done = new CompletableFuture<>();
		offer(new Job(() -> {
			try {
				done.complete(work.get());
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		}, done, null));
		return done.get(90, TimeUnit.SECONDS);
	}

	private CompletableFuture<String> submit(Supplier<String> work) {
		CompletableFuture<String> done = new CompletableFuture<>();
		offer(new Job(() -> {
			try {
				done.complete(work.get());
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		}, done, "ok"));
		return done;
	}

	private void offer(Job job) {
		synchronized (lock) {
			if (closed) throw new IllegalStateException("action queue closed");
			if (jobs.size() >= 32) throw new IllegalStateException("action queue full");
			jobs.add(job);
			lock.notifyAll();
		}
	}

	/** Drop queued work. A stop with nothing queued must not cancel the next action. */
	public String stop(Runnable release) {
		cancel = true;
		List<Job> dropped;
		synchronized (lock) {
			dropped = new ArrayList<>(jobs);
			jobs.clear();
			if (jobs.isEmpty()) cancel = false;
		}
		for (Job job : dropped) job.finish("cancelled");
		if (release != null) release.run();
		return "stopped";
	}

	public boolean cancelRequested() {
		return cancel;
	}

	private void run() {
		while (!closed) {
			Job job;
			synchronized (lock) {
				while (jobs.isEmpty() && !closed) {
					try {
						lock.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				if (closed) return;
				job = jobs.removeFirst();
			}
			if (cancel) {
				job.finish("cancelled");
				continue;
			}
			try {
				job.work.run();
				job.finish(job.fallback);
			} catch (Throwable t) {
				job.fail(t);
			} finally {
				cancel = false;
			}
		}
	}

	@Override
	public void close() {
		closed = true;
		synchronized (lock) {
			lock.notifyAll();
		}
		worker.interrupt();
	}

	private record Job(Runnable work, CompletableFuture done, Object fallback) {
		@SuppressWarnings("unchecked")
		void finish(Object value) {
			if (!done.isDone()) done.complete(value);
		}

		void fail(Throwable error) {
			if (!done.isDone()) done.completeExceptionally(error);
		}
	}
}
