/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.learning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/// A simulated model for the learning tests. It gives its scripted replies in order, whatever is asked, and
/// keeps every question and transcript so a test can assert what the conversation sent it.
///
/// Running out of replies fails the test rather than acting as a refusal, because a conversation that asks
/// more questions than the test expected is exactly what the test should catch.
class CannedAdvisor implements Advisor {

    private final Deque<String> replies;
    private final List<Question> questions = new ArrayList<>();
    private final List<List<Exchange>> transcripts = new ArrayList<>();

    CannedAdvisor(final String... replies) {
        this.replies = new ArrayDeque<>(List.of(replies));
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        if (replies.isEmpty()) {
            throw new AssertionError("The conversation asked more than was scripted: " + question);
        }
        questions.add(question);
        transcripts.add(transcript);
        return replies.pop();
    }

    List<Question> questions() {
        return questions;
    }

    /// Returns the transcript sent with a given question.
    ///
    /// @param index The zero-based index of the question.
    /// @return The earlier questions and answers that accompanied that question.
    List<Exchange> transcriptAt(final int index) {
        return transcripts.get(index);
    }

    static String fenced(final String document) {
        return "Here is the configuration:\n```xml\n" + document + "\n```\n";
    }
}
