/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.stage.Guidance.Given;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// An advisor that keeps the attempt's claim alive while a question is with the model (A45; design 04
/// §4). The claim is pushed out before every question and after every answer, but a single call that
/// takes longer than the lease would otherwise let the claim lapse mid-call, and another node would take
/// the attempt and ask the same question again. So while the delegate is answering, the heartbeat runs
/// on a timer — a third of the lease apart, so two can be missed before the claim lapses — and stops the
/// moment the answer, or a failure, comes back.
///
/// One daemon thread serves every attempt on the node: a heartbeat is one short write, and an attempt
/// asks one question at a time. A question's beats run under a lock that the question takes as it
/// returns, so once [#ask] has returned no beat of it is running and none can start: the session goes on
/// — parks, closes, asks again — knowing nothing will write its claim behind it.
public final class HeartbeatingAdvisor implements Advisor {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "shapeshifter-ai-claim-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final Advisor delegate;
    private final Runnable heartbeat;
    private final long everyMs;

    /// @param delegate  Who answers.
    /// @param heartbeat What pushes the claim out; it must not throw.
    /// @param every     How often to push it out while a question is with the model.
    public HeartbeatingAdvisor(final Advisor delegate, final Runnable heartbeat, final Duration every) {
        this.delegate = Objects.requireNonNull(delegate);
        this.heartbeat = Objects.requireNonNull(heartbeat);
        this.everyMs = Math.max(1L, every.toMillis());
    }

    /// The interval for a lease: a third of it, so that two missed beats still leave the claim held.
    public static Duration intervalFor(final Duration lease) {
        return lease.dividedBy(3);
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        final Beating beating = new Beating();
        try {
            return delegate.ask(transcript, question);
        } finally {
            beating.stop();
        }
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question, final List<Given> guidance) {
        final Beating beating = new Beating();
        try {
            return delegate.ask(transcript, question, guidance);
        } finally {
            beating.stop();
        }
    }

    @Override
    public List<Long> carried(final List<Given> offered) {
        return delegate.carried(offered);
    }

    @Override
    public long tokensUsed() {
        return delegate.tokensUsed();
    }

    @Override
    public boolean replaying() {
        return delegate.replaying();
    }


    // --------------------------------------------------------------------------------


    /// One question's beats: started with it, and stopped — waiting for a beat that is running — before
    /// the question returns.
    private final class Beating {

        private final Object lock = new Object();
        private boolean out = true;
        private final ScheduledFuture<?> future;

        private Beating() {
            future = TIMER.scheduleAtFixedRate(this::beat, everyMs, everyMs, TimeUnit.MILLISECONDS);
        }

        private void beat() {
            synchronized (lock) {
                if (out) {
                    heartbeat.run();
                }
            }
        }

        private void stop() {
            synchronized (lock) {
                out = false;
            }
            future.cancel(false);
        }
    }
}
