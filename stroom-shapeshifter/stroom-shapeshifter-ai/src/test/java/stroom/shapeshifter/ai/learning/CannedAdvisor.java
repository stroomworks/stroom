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

package stroom.shapeshifter.ai.learning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The simulated model: replies in the order scripted, whatever is asked, and keeps every question so a
 * test can assert what the conversation put to it. Running out of script is a test failure, not a refusal —
 * a conversation asking more than the test expected is the thing under test.
 */
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

    /**
     * @return The transcript that accompanied the nth question.
     */
    List<Exchange> transcriptAt(final int index) {
        return transcripts.get(index);
    }

    static String fenced(final String document) {
        return "Here is the configuration:\n```xml\n" + document + "\n```\n";
    }
}
