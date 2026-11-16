/*
 * Copyright 2025 Crown Copyright
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
import stroom.util.jersey.HttpClientProviderCache;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import java.time.Duration;

/// The langchain4j HTTP client builder for a model, over the HTTP client configuration its document gives.
///
/// That configuration decides the timeouts. langchain4j's model builders call [#connectTimeout(Duration)]
/// and [#readTimeout(Duration)] every time a model is built, with defaults of their own (15 seconds to
/// connect, 60 seconds to read) wherever the model builder was not given a timeout. Were those calls
/// honoured, no model call could take longer than a minute, whatever the document said, so they are
/// ignored.
public class ApacheHttpClientBuilder implements HttpClientBuilder {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ApacheHttpClientBuilder.class);

    private final HttpClientProviderCache httpClientProviderCache;
    private final HttpClientConfiguration httpClientConfiguration;

    /// @param httpClientProviderCache The cache of HTTP clients, keyed by their configuration.
    /// @param httpClientConfiguration The configuration the model's document gives, which decides the
    ///                                timeouts of every call the model makes.
    public ApacheHttpClientBuilder(final HttpClientProviderCache httpClientProviderCache,
                                   final HttpClientConfiguration httpClientConfiguration) {
        this.httpClientProviderCache = httpClientProviderCache;
        this.httpClientConfiguration = httpClientConfiguration;
    }

    /// @return The connection timeout the configuration gives, or the default where it gives none.
    @Override
    public Duration connectTimeout() {
        return NullSafe.getOrElse(httpClientConfiguration,
                        HttpClientConfiguration::getConnectionTimeout,
                        HttpClientConfiguration.DEFAULT_CONNECTION_TIMEOUT)
                .getDuration();
    }

    /// Ignored: the configuration given at construction decides the connection timeout (see the class).
    ///
    /// @param timeout The timeout langchain4j asks for, which is not used.
    /// @return This builder, unchanged.
    @Override
    public HttpClientBuilder connectTimeout(final Duration timeout) {
        LOGGER.debug(() -> "connectTimeout() - ignoring " + timeout + " in favour of the configured "
                           + connectTimeout());
        return this;
    }

    /// @return The read timeout the configuration gives, or the default where it gives none.
    @Override
    public Duration readTimeout() {
        return NullSafe.getOrElse(httpClientConfiguration,
                        HttpClientConfiguration::getTimeout,
                        HttpClientConfiguration.DEFAULT_TIMEOUT)
                .getDuration();
    }

    /// Ignored: the configuration given at construction decides the read timeout (see the class).
    ///
    /// @param timeout The timeout langchain4j asks for, which is not used.
    /// @return This builder, unchanged.
    @Override
    public HttpClientBuilder readTimeout(final Duration timeout) {
        LOGGER.debug(() -> "readTimeout() - ignoring " + timeout + " in favour of the configured "
                           + readTimeout());
        return this;
    }

    /// @return A client that makes its calls with the configuration given at construction.
    @Override
    public HttpClient build() {
        return new ApacheHttpClient(httpClientProviderCache, httpClientConfiguration);
    }
}
