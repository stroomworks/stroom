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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestHeartbeatingAdvisor {

    private static final Duration EVERY = Duration.ofMillis(5);

    @Test
    void theClaimIsPushedOutWhileTheModelIsAnswering() throws InterruptedException {
        // A45: a call longer than the lease must not let the claim lapse. The model here answers only once
        // the claim has been pushed out three times while it was working.
        final AtomicInteger beats = new AtomicInteger();
        final CountDownLatch threeBeats = new CountDownLatch(3);
        final Advisor slow = (transcript, question) -> {
            try {
                assertThat(threeBeats.await(5, TimeUnit.SECONDS)).describedAs("beaten while answering").isTrue();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "answer";
        };
        final HeartbeatingAdvisor advisor = new HeartbeatingAdvisor(slow, () -> {
            beats.incrementAndGet();
            threeBeats.countDown();
        }, EVERY);

        assertThat(advisor.ask(List.of(), null)).isEqualTo("answer");

        // And it stops with the answer: no beat for a question that is no longer out, not even one that
        // was running as the answer came back.
        final int atAnswer = beats.get();
        Thread.sleep(EVERY.toMillis() * 10);
        assertThat(beats.get()).describedAs("no beats once answered").isEqualTo(atAnswer);
    }

    @Test
    void theHeartbeatStopsWhenTheModelFails() throws InterruptedException {
        final AtomicInteger beats = new AtomicInteger();
        final Advisor failing = (transcript, question) -> {
            throw new IllegalStateException("the model is down");
        };
        final HeartbeatingAdvisor advisor = new HeartbeatingAdvisor(failing, beats::incrementAndGet, EVERY);

        assertThatThrownBy(() -> advisor.ask(List.of(), null, List.of()))
                .isInstanceOf(IllegalStateException.class);
        final int atFailure = beats.get();
        Thread.sleep(EVERY.toMillis() * 10);
        assertThat(beats.get()).describedAs("no beats for a question that failed").isEqualTo(atFailure);
    }

    @Test
    void whatIsNotAQuestionIsTheDelegatesAndBeatsNothing() {
        final AtomicInteger beats = new AtomicInteger();
        final Advisor counting = new Advisor() {
            @Override
            public String ask(final List<Exchange> transcript, final Question question) {
                return "";
            }

            @Override
            public long tokensUsed() {
                return 42L;
            }

            @Override
            public boolean replaying() {
                return true;
            }
        };
        final HeartbeatingAdvisor advisor = new HeartbeatingAdvisor(counting, beats::incrementAndGet, EVERY);

        assertThat(advisor.tokensUsed()).isEqualTo(42L);
        assertThat(advisor.replaying()).isTrue();
        assertThat(beats.get()).isZero();
    }

    @Test
    void theIntervalIsAThirdOfTheLease() {
        assertThat(HeartbeatingAdvisor.intervalFor(Duration.ofMinutes(10))).isEqualTo(Duration.ofSeconds(200));
    }
}
