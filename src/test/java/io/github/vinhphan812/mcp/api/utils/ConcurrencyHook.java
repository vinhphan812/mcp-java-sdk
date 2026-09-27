/*
 * Copyright 2025 Phan Thanh Vinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vinhphan812.mcp.api.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only handler gate. Each admitted handler signals entry then waits until the test releases it,
 * allowing an overflow request to observe the held concurrent slot.
 */
public final class ConcurrencyHook {
    private final CountDownLatch entered;
    private final Semaphore release = new Semaphore(0);
    private final int expectedEntrants;
    private final AtomicInteger remainingEntrants;

    public ConcurrencyHook(int expectedEntrants) {
        if (expectedEntrants < 1) {
            throw new IllegalArgumentException("expectedEntrants must be positive");
        }
        this.expectedEntrants = expectedEntrants;
        this.remainingEntrants = new AtomicInteger(expectedEntrants);
        this.entered = new CountDownLatch(expectedEntrants);
    }

    /** Signals handler entry and blocks only the expected admitted handlers until released. */
    public void blockIfAdmitted() throws InterruptedException {
        if (claimEntrant()) {
            entered.countDown();
            try {
                release.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private boolean claimEntrant() {
        for (;;) {
            int remaining = remainingEntrants.get();
            if (remaining == 0) {
                return false;
            }
            if (remainingEntrants.compareAndSet(remaining, remaining - 1)) {
                return true;
            }
        }
    }

    /** Returns whether every expected admitted handler entered before the deadline. */
    public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
        return entered.await(timeout, unit);
    }

    /** Releases every expected admitted handler; extra permits make cleanup idempotent. */
    public void unblockAll() {
        release.release(expectedEntrants);
    }
}
