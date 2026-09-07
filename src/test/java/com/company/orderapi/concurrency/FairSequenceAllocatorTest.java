package com.company.orderapi.concurrency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** PR #30 unit test: the ReentrantLock-guarded allocator is concurrency-safe. */
class FairSequenceAllocatorTest {

    @Test
    void manyVirtualThreadsNeverProduceDuplicateOrMissingSequenceNumbers()
            throws Exception {
        FairSequenceAllocator allocator = new FairSequenceAllocator();
        int threads = 100;
        int perThread = 100;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    List<Long> mine = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        mine.add(allocator.next());
                    }
                    return mine;
                }));
            }
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                all.addAll(future.get());
            }

            Set<Long> unique = all.stream().collect(Collectors.toSet());
            Set<Long> expected = LongStream.rangeClosed(1, threads * perThread)
                    .boxed().collect(Collectors.toSet());
            assertThat(unique).isEqualTo(expected);
            assertThat(allocator.current()).isEqualTo(threads * perThread);
        }
    }
}
