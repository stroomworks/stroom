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

package stroom.learningflow.stage;

import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;
import stroom.learningflow.stage.Guidance.Given;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// An advisor wrapper that keeps a session's claim on its learner alive while a question is with the model.
///
/// The claim is renewed before every question and after every answer, but a single call that takes longer
/// than the lease would otherwise let the claim expire mid-call, and another node would take over the
/// session and ask the same question again. So while the delegate is answering, a heartbeat runs on a timer,
/// a third of the lease apart so that two can be missed before the claim expires, and stops as soon as the
/// answer, or a failure, comes back.
///
/// One daemon thread serves every session on the node, because a heartbeat is one short write and a session
/// asks one question at a time. A question's heartbeats run under a lock that the question also takes as it
/// returns, so once [#ask] has returned none of its heartbeats is running and none can start. The session
/// can then go on (park, close, or ask again) knowing that nothing will renew its claim behind its back.
public final class HeartbeatingAdvisor implements Advisor {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "learning-flow-claim-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final Advisor delegate;
    private final Runnable heartbeat;
    private final long everyMs;

    /// @param delegate  The advisor that answers the questions.
    /// @param heartbeat Renews the claim; it must not throw.
    /// @param every     How often to renew the claim while a question is with the model.
    public HeartbeatingAdvisor(final Advisor delegate, final Runnable heartbeat, final Duration every) {
        this.delegate = Objects.requireNonNull(delegate);
        this.heartbeat = Objects.requireNonNull(heartbeat);
        this.everyMs = Math.max(1L, every.toMillis());
    }

    /// Gets the heartbeat interval for a lease: a third of it, so that the claim is still held after two
    /// missed heartbeats.
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


    /// The heartbeats for one question: started when it is asked, and stopped before it returns, waiting
    /// for any heartbeat that is running to finish.
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
