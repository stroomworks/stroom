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

package stroom.ai.impl;

import stroom.cache.impl.CacheManagerImpl;
import stroom.credentials.api.HttpConfigResolver;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.util.http.HttpClientConfiguration;
import stroom.util.jersey.HttpClientProvider;
import stroom.util.jersey.HttpClientProviderCache;
import stroom.util.shared.http.HttpClientConfig;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;
import stroom.util.time.SimpleDurationUtil;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Covers the HTTP client configuration a model's calls are made with. The model document's timeouts and
/// retries must be the ones in force: langchain4j otherwise imposes its own (60 seconds to read, 15 to
/// connect, two retries), so that a call taking longer than a minute failed after three tries, whatever
/// the document said.
class TestAiServiceImpl {

    private static final Duration DOC_READ_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DOC_CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private final List<HttpClientConfiguration> callsMadeWith = new ArrayList<>();

    /// The regression: a chat call is made with the document's read and connect timeouts, not
    /// langchain4j's defaults of 60 and 15 seconds.
    @Test
    void chatModel_callsAreMadeWithTheDocumentsTimeouts() {
        final ChatModel chatModel = aiService().getChatModel(modelDoc(docHttpClientConfig(0)));

        assertThatThrownBy(() -> chatModel.chat("Hello"))
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        assertThat(callsMadeWith).hasSize(1);
        assertThat(callsMadeWith.getFirst().getTimeout().getDuration()).isEqualTo(DOC_READ_TIMEOUT);
        assertThat(callsMadeWith.getFirst().getConnectionTimeout().getDuration()).isEqualTo(DOC_CONNECT_TIMEOUT);
    }

    /// A call that times out is tried as many more times as the document's retries say, and no more:
    /// langchain4j's default would try it three times in all.
    @Test
    void chatModel_aFailedCallIsRetriedOnlyAsManyTimesAsTheDocumentSays() {
        final ChatModel chatModel = aiService().getChatModel(modelDoc(docHttpClientConfig(1)));

        assertThatThrownBy(() -> chatModel.chat("Hello"))
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        assertThat(callsMadeWith).hasSize(2);
        assertThat(callsMadeWith)
                .extracting(configuration -> configuration.getTimeout().getDuration())
                .containsOnly(DOC_READ_TIMEOUT);
    }

    /// A document with no HTTP client configuration gets the service's default read timeout of ten
    /// minutes, and no retries.
    @Test
    void chatModel_aDocumentWithNoHttpClientConfigurationGetsTheDefault() {
        final ChatModel chatModel = aiService().getChatModel(modelDoc(null));

        assertThatThrownBy(() -> chatModel.chat("Hello"))
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        assertThat(callsMadeWith).hasSize(1);
        assertThat(callsMadeWith.getFirst().getTimeout().getDuration()).isEqualTo(Duration.ofMinutes(10));
    }

    /// An embedding model is built the same way as a chat model, and had the same fault.
    @Test
    void embeddingModel_callsAreMadeWithTheDocumentsTimeoutsAndRetries() {
        final EmbeddingModel embeddingModel = aiService().getEmbeddingModel(modelDoc(docHttpClientConfig(0)));

        assertThatThrownBy(() -> embeddingModel.embed("Hello"))
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        assertThat(callsMadeWith).hasSize(1);
        assertThat(callsMadeWith.getFirst().getTimeout().getDuration()).isEqualTo(DOC_READ_TIMEOUT);
        assertThat(callsMadeWith.getFirst().getConnectionTimeout().getDuration()).isEqualTo(DOC_CONNECT_TIMEOUT);
    }

    /// Regression test for gh-6017. Now that the model document's timeouts are honoured, the default
    /// connection timeout must stay short so an unreachable model server is reported promptly, while the
    /// response timeout stays long enough for slow models.
    @Test
    void testDefaultHttpClientConfigTimeouts() {
        final HttpClientConfig config = aiService().getDefaultHttpClientConfig();

        assertThat(toDuration(config.getConnectionTimeout()))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(toDuration(config.getTimeout()))
                .isEqualTo(Duration.ofMinutes(10));
    }

    /// The default configuration is built once and reused.
    @Test
    void testDefaultHttpClientConfigIsReused() {
        final AiServiceImpl aiService = aiService();

        assertThat(aiService.getDefaultHttpClientConfig())
                .isSameAs(aiService.getDefaultHttpClientConfig());
    }

    // -----------------------------------------------------------------------------------------------

    private AiServiceImpl aiService() {
        return new AiServiceImpl(
                () -> null,
                () -> null,
                () -> null,
                new HttpConfigResolver(() -> null),
                this::recordingHttpClientCache,
                null,
                null,
                new CacheManagerImpl(),
                AiConfig::new);
    }

    /// Records the configuration of every call, then fails it as a model that answers too slowly does,
    /// so nothing is sent anywhere.
    private HttpClientProviderCache recordingHttpClientCache() {
        return new HttpClientProviderCache() {
            @Override
            public HttpClientProvider get(final HttpClientConfiguration httpClientConfiguration) {
                callsMadeWith.add(httpClientConfiguration);
                return failingProvider();
            }

            @Override
            public HttpClientProvider getAddressChecked(final HttpClientConfiguration httpClientConfiguration) {
                return get(httpClientConfiguration);
            }
        };
    }

    private static HttpClientProvider failingProvider() {
        throw new UncheckedIOException(new SocketTimeoutException("Read timed out"));
    }

    private static HttpClientConfig docHttpClientConfig(final int retries) {
        return HttpClientConfig
                .builder()
                .timeout(SimpleDuration.builder().time(30).timeUnit(TimeUnit.MINUTES).build())
                .connectionTimeout(SimpleDuration.builder().time(20).timeUnit(TimeUnit.SECONDS).build())
                .retries(retries)
                .build();
    }

    private static Duration toDuration(final SimpleDuration simpleDuration) {
        return SimpleDurationUtil.convertToStroomDuration(simpleDuration).getDuration();
    }

    private static OpenAIModelDoc modelDoc(final HttpClientConfig httpClientConfig) {
        return OpenAIModelDoc
                .builder()
                .uuid(UUID.randomUUID().toString())
                .name("Learning model")
                .modelId("test-model")
                .httpClientConfiguration(httpClientConfig)
                .build();
    }
}
