package net.snakestudios.sharkmcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionQueueTest {
	@Test
	void runsOneAtATimeInOrder() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			List<Integer> order = new ArrayList<>();
			CountDownLatch hold = new CountDownLatch(1);
			CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> {
						order.add(1);
						await(hold);
						order.add(2);
						return "first";
					});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			awaitStarted(order);
			CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> {
						order.add(3);
						return "second";
					});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			Thread.sleep(50);
			assertEquals(List.of(1), order);
			hold.countDown();
			assertEquals("first", first.get(5, TimeUnit.SECONDS));
			assertEquals("second", second.get(5, TimeUnit.SECONDS));
			assertEquals(List.of(1, 2, 3), order);
		}
	}

	@Test
	void stopOnAnEmptyQueueDoesNotCancelTheNextAction() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			assertEquals("stopped", queue.stop(null));
			assertFalse(queue.cancelRequested());
			assertEquals("still ran", queue.enqueue(() -> "still ran"));
		}
	}

	@Test
	void stopDropsWaitingWorkButFinishesTheRunningOneAsStoppedByTheCaller() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger ran = new AtomicInteger();
			CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> {
						started.countDown();
						await(release);
						ran.incrementAndGet();
						return "finished";
					});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> {
						ran.incrementAndGet();
						return "should not run";
					});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			Thread.sleep(40);
			AtomicInteger released = new AtomicInteger();
			assertEquals("stopped", queue.stop(released::incrementAndGet));
			assertEquals(1, released.get());
			assertEquals("cancelled", waiting.get(5, TimeUnit.SECONDS));
			release.countDown();
			assertEquals("finished", running.get(5, TimeUnit.SECONDS));
			assertEquals(1, ran.get());
			assertEquals("next", queue.enqueue(() -> "next"));
		}
	}

	@Test
	void stopWhileAJobIsQueuedCancelsIt() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> {
						started.countDown();
						await(release);
						return "hold";
					});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			CompletableFuture<String> queued = CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> "nope");
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			Thread.sleep(40);
			queue.stop(null);
			assertEquals("cancelled", queued.get(5, TimeUnit.SECONDS));
			release.countDown();
		}
	}

	@Test
	void failureSurfacesAndTheQueueContinues() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			ExecutionException failed = assertThrows(ExecutionException.class, () -> queue.enqueue(() -> {
				throw new IllegalStateException("boom");
			}));
			assertTrue(failed.getCause().getMessage().contains("boom"));
			assertEquals("ok", queue.enqueue(() -> "ok"));
		}
	}

	@Test
	void valueJobsReturnTheirValue() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			assertEquals(7, queue.enqueueValue(() -> 7));
		}
	}

	@Test
	void valueFailureSurfaces() {
		try (ActionQueue queue = new ActionQueue()) {
			ExecutionException failed = assertThrows(ExecutionException.class, () -> queue.enqueueValue(() -> {
				throw new IllegalArgumentException("bad image");
			}));
			assertTrue(failed.getCause().getMessage().contains("bad image"));
		}
	}

	@Test
	void thirtyThirdWaitingJobIsRejected() throws Exception {
		try (ActionQueue queue = new ActionQueue()) {
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			CompletableFuture.supplyAsync(() -> {
				try {
					return queue.enqueue(() -> {
						started.countDown();
						await(release);
						return "hold";
					});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			List<CompletableFuture<String>> waiting = new ArrayList<>();
			for (int i = 0; i < 32; i++) {
				waiting.add(CompletableFuture.supplyAsync(() -> {
					try {
						return queue.enqueue(() -> "queued");
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}));
			}
			Thread.sleep(80);
			IllegalStateException full = assertThrows(IllegalStateException.class, () -> queue.enqueue(() -> "overflow"));
			assertTrue(full.getMessage().contains("full"));
			release.countDown();
			for (CompletableFuture<String> job : waiting) assertEquals("queued", job.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void closedQueueRejectsNewWork() {
		ActionQueue queue = new ActionQueue();
		queue.close();
		assertThrows(IllegalStateException.class, () -> queue.enqueue(() -> "no"));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void awaitStarted(List<Integer> order) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (order.isEmpty()) {
			if (System.currentTimeMillis() > deadline) throw new AssertionError("job never started");
			Thread.sleep(10);
		}
	}
}
