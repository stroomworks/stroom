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

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.event.logging.api.DocumentEventLog;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.QuestionText;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Guidance.Given;
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

/// The [Advisor] used on a node, which asks a real model. It sends the model's chat model the system
/// text, then the attempt's transcript so far, then the new question, all worded by [QuestionText], so the
/// model sees the whole conversation.
///
/// Every call is audited against the Learning Flow document, recording the model, the kind of question,
/// the tokens used and the outcome, because promotion can be automatic with nobody watching. The sample
/// data itself is not written to the audit: samples are never redacted, so writing them would put a
/// second copy of the feed's data in the audit log.
///
/// Transport retries are handled by the chat model's client (it retries a dropped connection before
/// this class sees it), so an HTTP 503 does not count as a failed candidate. The attempt's token budget is
/// fed by [#tokensUsed()].
///
/// There is no response cache between this and the model, because asking again with the same words
/// must reach the model.
public final class ModelAdvisor implements Advisor {

    static final String EVENT_TYPE = "LearningFlowAsk";

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

    /// Asks a question, sending the document's system text followed by any guidance people have left for
    /// this learner, then the conversation so far.
    ///
    /// Guidance goes in the **system** message beside the document's own instructions, because it is a
    /// standing fact about the feed, not a turn of the conversation. As a turn it would need an answer; in
    /// the system message it simply applies to every question that follows.
    @Override
    public String ask(final List<Exchange> transcript, final Question question, final List<Given> guidance) {
        final List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(words.system() + Guidance.asSaid(guidance)));
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
            // A reply with no message, or a message with no text, is treated as an empty answer.
            final String reply = NullSafe.getOrElse(response.aiMessage(), AiMessage::text, "");
            eventLog.process(document, EVENT_TYPE, "Asked " + modelName + " a " + kind + " question, turn "
                                                   + (transcript.size() + 1) + ": " + used + " tokens, "
                                                   + reply.length() + " characters back", null);
            return reply;
        } catch (final RuntimeException e) {
            eventLog.process(document, EVENT_TYPE, "Asked " + modelName + " a " + kind + " question, turn "
                                                   + (transcript.size() + 1) + ": failed", e);
            throw e;
        }
    }

    @Override
    public long tokensUsed() {
        return tokens.get();
    }
}
