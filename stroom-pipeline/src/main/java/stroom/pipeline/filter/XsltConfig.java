/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.pipeline.filter;

import stroom.util.cache.CacheConfig;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;
import stroom.util.time.StroomDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;


@JsonPropertyOrder(alphabetic = true)
public class XsltConfig extends AbstractConfig implements IsStroomConfig {

    private static final int DEFAULT_MAX_ELEMENTS = 1000000;

    /**
     * A safety net rather than a target, and deliberately nowhere near the observed cost. Measured over a
     * real 51 stylesheet deployment, reference parsing takes a mean of 1.1ms per stylesheet warm, with a
     * 95th percentile of 2.4ms and a worst case of 5.9ms; the first parse after a restart costs a further
     * 86ms of Saxon class loading, once. Five seconds therefore leaves nearly three orders of magnitude of
     * headroom, which is what a bound meant only to catch pathological input should look like.
     */
    private static final StroomDuration DEFAULT_REFERENCE_PARSE_TIMEOUT = StroomDuration.ofSeconds(5);

    /**
     * How far to follow a variable that is defined in terms of other variables. Real stylesheets nest a
     * handful of levels at most, so this is only ever reached by pathological or circular input.
     */
    private static final int DEFAULT_MAX_REFERENCE_VARIABLE_DEPTH = 10;

    private final CacheConfig cacheConfig;
    private final int maxElements;
    private final StroomDuration referenceParseTimeout;
    private final int maxReferenceVariableDepth;

    public XsltConfig() {
        cacheConfig = CacheConfig.builder()
                .maximumSize(1000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .build();
        maxElements = DEFAULT_MAX_ELEMENTS;
        referenceParseTimeout = DEFAULT_REFERENCE_PARSE_TIMEOUT;
        maxReferenceVariableDepth = DEFAULT_MAX_REFERENCE_VARIABLE_DEPTH;
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public XsltConfig(@JsonProperty("cache") final CacheConfig cacheConfig,
                      @JsonProperty("maxElements") final Integer maxElements,
                      @JsonProperty("referenceParseTimeout") final StroomDuration referenceParseTimeout,
                      @JsonProperty("maxReferenceVariableDepth") final Integer maxReferenceVariableDepth) {
        this.cacheConfig = cacheConfig;
        this.maxElements = Objects.requireNonNullElse(maxElements, DEFAULT_MAX_ELEMENTS);
        this.referenceParseTimeout = Objects.requireNonNullElse(
                referenceParseTimeout, DEFAULT_REFERENCE_PARSE_TIMEOUT);
        this.maxReferenceVariableDepth = Objects.requireNonNullElse(
                maxReferenceVariableDepth, DEFAULT_MAX_REFERENCE_VARIABLE_DEPTH);
    }

    @JsonProperty("cache")
    @JsonPropertyDescription("The cache config for the XSLT pool.")
    public CacheConfig getCacheConfig() {
        return cacheConfig;
    }

    @JsonPropertyDescription("The maximum number of elements that the XSLT filter will expect to receive before " +
            "it errors. This protects Stroom from running out of memory in cases where an appropriate XML splitter " +
            "has not been used in a pipeline.")
    public int getMaxElements() {
        return maxElements;
    }

    @JsonPropertyDescription("How long to spend working out what one XSLT refers to before giving up. " +
            "Exceeding it is not an error: whatever was found is kept, the result is marked incomplete, and " +
            "the document still saves. This is a safety net for pathological input, not a performance " +
            "target, so it is set well above the time a real stylesheet takes.")
    public StroomDuration getReferenceParseTimeout() {
        return referenceParseTimeout;
    }

    @JsonPropertyDescription("How far to follow a variable defined in terms of other variables when working " +
            "out what an XSLT refers to. Beyond this depth a value is reported as undeterminable rather " +
            "than pursued further. Also bounds circular definitions, which are detected separately.")
    public int getMaxReferenceVariableDepth() {
        return maxReferenceVariableDepth;
    }

    @Override
    public String toString() {
        return "XsltConfig{" +
                "cacheConfig=" + cacheConfig +
                ", maxElements=" + maxElements +
                ", referenceParseTimeout=" + referenceParseTimeout +
                ", maxReferenceVariableDepth=" + maxReferenceVariableDepth +
                '}';
    }
}
