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
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.QuestionText;
import stroom.shapeshifter.ai.learning.Sample;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node's advisor against a chat model that records what it was sent: the system text first, the
 * transcript as alternating turns, the new question last; tokens counted; every call audited.
 */
class TestModelAdvisor {

    private static final DocRef DOCUMENT = new DocRef("ShapeshifterAi", "doc-1", "door-access");
    private static final Sample SAMPLE = Sample.of("a,b\n");

    /**
     * A chat model that answers from a list and keeps every request.
     */
    private static final class Recording implements ChatModel {

        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<String> replies;
        private final RuntimeException failure;

        private Recording(final List<String> replies, final RuntimeException failure) {
            this.replies = new ArrayList<>(replies);
            this.failure = failure;
        }

        @Override
        public ChatResponse doChat(final ChatRequest request) {
            requests.add(request.messages());
            if (failure != null) {
                throw failure;
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(replies.remove(0)))
                    .tokenUsage(new TokenUsage(100, 20))
                    .build();
        }
    }

    @Test
    void putsTheSystemTextTheTranscriptAndTheQuestion() {
        final Recording model = new Recording(List.of("DSParser -> XSLTFilter", "```xml\n<x/>\n```"), null);
        final DocumentEventLog eventLog = Mockito.mock(DocumentEventLog.class);
        final QuestionText words = QuestionText.builtIn("Badge readers.");
        final ModelAdvisor advisor = new ModelAdvisor(model, "test-model", DOCUMENT, words, eventLog);

        final Chain chain = new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of());
        final String first = advisor.ask(List.of(), chain);
        final Configuration configuration = new Configuration("DSParser", "TextConverter", SAMPLE, "a,b\n", null,
                null, List.of(), false, Question.Records.UNKNOWN, List.of(), false, List.of());
        final String second = advisor.ask(List.of(new Exchange(chain, first)), configuration);

        assertThat(first).isEqualTo("DSParser -> XSLTFilter");
        assertThat(second).contains("<x/>");
        final List<ChatMessage> request = model.requests.get(1);
        assertThat(request).hasSize(4);
        assertThat(((SystemMessage) request.get(0)).text()).isEqualTo(words.system());
        assertThat(((UserMessage) request.get(1)).singleText()).isEqualTo(words.render(chain));
        assertThat(((AiMessage) request.get(2)).text()).isEqualTo(first);
        assertThat(((UserMessage) request.get(3)).singleText()).isEqualTo(words.render(configuration));
        assertThat(advisor.tokensUsed()).isEqualTo(240);

        final ArgumentCaptor<String> descriptions = ArgumentCaptor.forClass(String.class);
        Mockito.verify(eventLog, Mockito.times(2)).process(Mockito.eq(DOCUMENT), Mockito.eq(ModelAdvisor.EVENT_TYPE),
                descriptions.capture(), Mockito.isNull());
        assertThat(descriptions.getAllValues().get(0))
                .contains("test-model").contains("Chain question, turn 1").contains("120 tokens");
        assertThat(descriptions.getAllValues().get(1)).contains("Configuration question, turn 2");
        assertThat(descriptions.getAllValues()).noneMatch(text -> text.contains("a,b"));
    }

    @Test
    void aFailedCallIsAuditedAndRethrown() {
        final RuntimeException boom = new IllegalStateException("503");
        final Recording model = new Recording(List.of(), boom);
        final DocumentEventLog eventLog = Mockito.mock(DocumentEventLog.class);
        final ModelAdvisor advisor = new ModelAdvisor(model, "test-model", DOCUMENT, QuestionText.builtIn(null),
                eventLog);

        assertThatThrownBy(() -> advisor.ask(List.of(), new Chain(SAMPLE, List.of("DSParser"), List.of())))
                .isSameAs(boom);
        Mockito.verify(eventLog).process(Mockito.eq(DOCUMENT), Mockito.eq(ModelAdvisor.EVENT_TYPE),
                Mockito.contains("failed"), Mockito.same(boom));
        assertThat(advisor.tokensUsed()).isZero();
    }
}
