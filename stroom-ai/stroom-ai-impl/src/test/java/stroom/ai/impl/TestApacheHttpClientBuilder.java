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

import stroom.util.http.HttpClientConfiguration;
import stroom.util.time.StroomDuration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/// The configuration given at construction decides the timeouts; what langchain4j asks for does not.
class TestApacheHttpClientBuilder {

    /// The timeouts reported are the configuration's.
    @Test
    void timeouts_areTheConfigurations() {
        final ApacheHttpClientBuilder builder = new ApacheHttpClientBuilder(null, configuration());

        assertThat(builder.readTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(builder.connectTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    /// langchain4j sets 60 and 15 seconds on every model it builds; the configuration still decides.
    @Test
    void timeoutsAskedForByLangchain4j_areIgnored() {
        final ApacheHttpClientBuilder builder = new ApacheHttpClientBuilder(null, configuration());

        assertThat(builder.readTimeout(Duration.ofSeconds(60))).isSameAs(builder);
        assertThat(builder.connectTimeout(Duration.ofSeconds(15))).isSameAs(builder);

        assertThat(builder.readTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(builder.connectTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    /// With no configuration, the defaults apply.
    @Test
    void noConfiguration_givesTheDefaults() {
        final ApacheHttpClientBuilder builder = new ApacheHttpClientBuilder(null, null);

        assertThat(builder.readTimeout()).isEqualTo(HttpClientConfiguration.DEFAULT_TIMEOUT.getDuration());
        assertThat(builder.connectTimeout())
                .isEqualTo(HttpClientConfiguration.DEFAULT_CONNECTION_TIMEOUT.getDuration());
    }

    private static HttpClientConfiguration configuration() {
        return HttpClientConfiguration
                .builder()
                .timeout(StroomDuration.ofMinutes(30))
                .connectionTimeout(StroomDuration.ofSeconds(20))
                .build();
    }
}
