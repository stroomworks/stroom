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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.event.logging.api.DocumentEventLog;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.QuestionText;
import stroom.shapeshifter.ai.stage.Guidance.Given;
import stroom.util.shared.NullSafe;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The node's advisor (design 01 §10, §12 item 6): a model document's chat model, asked the questions of
 * {@link QuestionText} — the words the live smoke measured — after the system text and the attempt's
 * transcript so far, so the model sees the whole conversation. Every invocation is audited against the
 * Shapeshifter AI document with the model, the kind of question, the tokens charged and the outcome,
 * as A9 requires of a change nobody watches; the sample itself is not written to the audit, since A17's
 * redaction is not built and the audit is not the place to leak what it would redact. Transport retries
 * are the chat model's own (the client retries a dropped connection before this sees it), so a 503 is not
 * a failed candidate; the attempt's budgets are the conversation's, fed by {@link #tokensUsed()}.
 * <p>
 * No response cache stands between this and the model: a re-ask with the same words must reach the model,
 * which is the cache-bypass §10 asks for.
 */
public final class ModelAdvisor implements Advisor {

    static final String EVENT_TYPE = "ShapeshifterAiAsk";

    private final ChatModel model;
    private final String modelName;
    private final DocRef document;
    private final QuestionText words;
    private final DocumentEventLog eventLog;
    private final AtomicLong tokens = new AtomicLong();

    public ModelAdvisor(final ChatModel model,
                        final String modelName,
                        final DocRef document,
                        final QuestionText words,
                        final DocumentEventLog eventLog) {
        this.model = model;
        this.modelName = modelName;
        this.document = document;
        this.words = words;
        this.eventLog = eventLog;
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        return ask(transcript, question, List.of());
    }

    /// The document's words, then what a supervisor has said about this learner (A46), then the conversation.
    ///
    /// Guidance goes in the **system** message beside the document's own instructions, because that is
    /// what it is: a standing fact about the feed, not a turn of the conversation. Put in as a turn it
    /// would have to be answered; put in here it is simply true for every question that follows.
    @Override
    public String ask(final List<Exchange> transcript, final Question question, final List<Given> guidance) {
        final List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(words.system() + guidance(guidance)));
        for (final Exchange exchange : transcript) {
            messages.add(UserMessage.from(words.render(exchange.question())));
            messages.add(AiMessage.from(exchange.reply()));
        }
        messages.add(UserMessage.from(words.render(question)));

        final String kind = question.getClass().getSimpleName();
        try {
            final ChatResponse response = model.chat(messages);
            final long used = response.tokenUsage() == null || response.tokenUsage().totalTokenCount() == null
                    ? 0
                    : response.tokenUsage().totalTokenCount();
            tokens.addAndGet(used);
            final String reply = response.aiMessage() == null
                    ? ""
                    : response.aiMessage().text();
            eventLog.process(document, EVENT_TYPE, "Asked " + modelName + " a " + kind + " question, turn "
                                                   + (transcript.size() + 1) + ": " + used + " tokens, "
                                                   + (reply == null
                    ? 0
                    : reply.length()) + " characters back", null);
            return reply == null
                    ? ""
                    : reply;
        } catch (final RuntimeException e) {
            eventLog.process(document, EVENT_TYPE, "Asked " + modelName + " a " + kind + " question, turn "
                                                   + (transcript.size() + 1) + ": failed", e);
            throw e;
        }
    }

    /// What a supervisor has told this stage about this learner, oldest first and attributed. Attributed
    /// because the model should weigh a person's words as a person's, and because the transcript a
    /// person reads afterwards has to show whose they were.
    private static String guidance(final List<Given> guidance) {
        if (NullSafe.isEmptyCollection(guidance)) {
            return "";
        }
        return guidance.stream()
                .map(given -> "- " + given.message().strip() + " (" + given.author() + ")")
                .collect(Collectors.joining("\n",
                        "\n\nA supervisor of this feed has said:\n", ""));
    }

    @Override
    public long tokensUsed() {
        return tokens.get();
    }
}
