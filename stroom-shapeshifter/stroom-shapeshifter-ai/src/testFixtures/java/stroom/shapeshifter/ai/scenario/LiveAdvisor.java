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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.QuestionText;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

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

/**
 * A real model behind the {@link Advisor} seam, for the live smoke of design 02 §6.1: the scenarios run
 * as written, with a model answering in place of a {@link Script}. Each question is put as
 * {@link QuestionText} renders it, after the system text and the attempt's transcript so far, so the
 * model sees the whole conversation as design 01 §10 requires. Usage is counted for the write-up.
 * <p>
 * Configured from the environment, as {@code TestExtractionReconstruction} is: {@code SHAPESHIFTER_AI_BASE_URL}
 * and {@code SHAPESHIFTER_LEARNING_MODEL} for an OpenAI-compatible endpoint, {@code SHAPESHIFTER_AI_API_KEY}
 * if it wants one, {@code SHAPESHIFTER_AI_TEMPERATURE} only for a model that takes one. Samples go to
 * that endpoint unredacted (A17 is not built), so it must be one the data may be sent to.
 */
public final class LiveAdvisor implements Advisor {

    public static final String BASE_URL = "SHAPESHIFTER_AI_BASE_URL";
    public static final String MODEL = "SHAPESHIFTER_LEARNING_MODEL";
    public static final String API_KEY = "SHAPESHIFTER_AI_API_KEY";
    /**
     * A sampling temperature, sent only when set: the newest models refuse the parameter outright, and
     * the ones that take it default sensibly.
     */
    public static final String TEMPERATURE = "SHAPESHIFTER_AI_TEMPERATURE";

    private final ChatModel model;
    private final QuestionText words;
    private final List<Turn> turns = new ArrayList<>();
    private long tokens;
    private long millis;

    public LiveAdvisor(final ChatModel model, final QuestionText words) {
        this.model = model;
        this.words = words;
    }

    /**
     * @return An advisor over the endpoint the environment names, speaking for the document as the node's
     * advisor would, or empty when it names none.
     */
    public static Optional<LiveAdvisor> fromEnvironment(final ShapeshifterAiDoc doc) {
        return modelFromEnvironment().map(model -> new LiveAdvisor(model, QuestionText.of(doc)));
    }

    /**
     * The chat model the environment names, or empty when it names none: for whatever else wants to put a
     * question to the same endpoint the same way.
     */
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

    /**
     * One question put and answered, with what it cost.
     */
    public record Turn(String kind, String prompt, String reply, long tokens, long millis) {

    }
}
