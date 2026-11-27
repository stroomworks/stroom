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

package stroom.learningflow.scenario;

import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.QuestionText;
import stroom.learningflow.shared.LearningFlowDoc;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// An [Advisor] backed by a real model, for the live tests (such as `TestLiveScenarios`), which run the
/// scenarios with a model answering in place of a [Script].
///
/// Each question is rendered by [QuestionText] and sent after the system text and the attempt's
/// conversation so far, so the model sees the whole conversation, as it would in a node. Tokens and time
/// are counted for the test report.
///
/// It is configured from the environment, as `TestExtractionReconstruction` is: `LEARNING_FLOW_BASE_URL`
/// and `LEARNING_FLOW_LEARNING_MODEL` for an OpenAI-compatible endpoint, `LEARNING_FLOW_API_KEY` if the
/// endpoint needs one, and `LEARNING_FLOW_TEMPERATURE` only for a model that accepts it. Samples are sent
/// to the endpoint unredacted, so it must be one the data may be sent to.
public final class LiveAdvisor implements Advisor {

    public static final String BASE_URL = "LEARNING_FLOW_BASE_URL";
    public static final String MODEL = "LEARNING_FLOW_LEARNING_MODEL";
    public static final String API_KEY = "LEARNING_FLOW_API_KEY";
    /// A sampling temperature, sent only when set, because the newest models reject the parameter and the
    /// ones that accept it have sensible defaults.
    public static final String TEMPERATURE = "LEARNING_FLOW_TEMPERATURE";

    private final ChatModel model;
    private final QuestionText words;
    private final List<Turn> turns = new ArrayList<>();
    private long tokens;
    private long millis;

    public LiveAdvisor(final ChatModel model, final QuestionText words) {
        this.model = model;
        this.words = words;
    }

    /// Creates an advisor for the endpoint named in the environment, which asks questions for the document
    /// as a node's advisor would.
    ///
    /// @return The advisor, or empty if the environment names no endpoint.
    public static Optional<LiveAdvisor> fromEnvironment(final LearningFlowDoc doc) {
        return modelFromEnvironment().map(model -> new LiveAdvisor(model, QuestionText.of(doc)));
    }

    /// Returns the chat model named in the environment, or empty if none is named, for other code that
    /// needs to call the same endpoint in the same way.
    public static Optional<ChatModel> modelFromEnvironment() {
        final String baseUrl = System.getenv(BASE_URL);
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        final OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(System.getenv(MODEL))
                .apiKey(Optional.ofNullable(System.getenv(API_KEY)).orElse("unused"))
                .timeout(Duration.ofMinutes(5))
                .maxRetries(2);
        Optional.ofNullable(System.getenv(TEMPERATURE))
                .filter(value -> !value.isBlank())
                .ifPresent(value -> builder.temperature(Double.parseDouble(value)));
        return Optional.of(builder.build());
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        final List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(words.system()));
        for (final Exchange exchange : transcript) {
            messages.add(UserMessage.from(words.render(exchange.question())));
            messages.add(AiMessage.from(exchange.reply()));
        }
        final String prompt = words.render(question);
        messages.add(UserMessage.from(prompt));

        final long started = System.currentTimeMillis();
        final ChatResponse response = model.chat(messages);
        final long took = System.currentTimeMillis() - started;
        final long used = response.tokenUsage() == null
                ? 0
                : response.tokenUsage().totalTokenCount();
        tokens += used;
        millis += took;
        final String reply = response.aiMessage().text();
        turns.add(new Turn(question.getClass().getSimpleName(), prompt, reply, used, took));
        return reply;
    }

    public List<Turn> turns() {
        return List.copyOf(turns);
    }

    public long tokens() {
        return tokens;
    }

    public long millis() {
        return millis;
    }

    /// One question asked and answered, with its cost in tokens and time.
    public record Turn(String kind, String prompt, String reply, long tokens, long millis) {

    }
}
