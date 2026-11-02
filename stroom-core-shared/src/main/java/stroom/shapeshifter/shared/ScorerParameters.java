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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a scorer of design §8.4 needs to know beyond its weight and threshold. One class per scorer that
 * takes parameters; the compile gate and input coverage take none. {@link #scorerType()} says which
 * scorer a set of parameters belongs to, and {@link ScorerSetting} refuses a mismatch.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = YieldParameters.class, name = "yield"),
        @JsonSubTypes.Type(value = SchemaConformanceParameters.class, name = "schemaConformance"),
        @JsonSubTypes.Type(value = ExtractionQualityParameters.class, name = "extractionQuality"),
        @JsonSubTypes.Type(value = BusinessRulesParameters.class, name = "businessRules"),
        @JsonSubTypes.Type(value = ErrorLoadParameters.class, name = "errorLoad"),
        @JsonSubTypes.Type(value = EventClassificationParameters.class, name = "eventClassification")
})
@JsonInclude(Include.NON_NULL)
public abstract sealed class ScorerParameters
        permits YieldParameters,
        SchemaConformanceParameters,
        ExtractionQualityParameters,
        BusinessRulesParameters,
        ErrorLoadParameters,
        EventClassificationParameters {

    public abstract ScorerType scorerType();

    /**
     * The parameters a scorer starts with when it is added to a Shapeshifter AI document, or null for a scorer that
     * takes
     * none.
     */
    public static ScorerParameters defaultsFor(final ScorerType type) {
        return switch (type) {
            case COMPILE, INPUT_COVERAGE -> null;
            case YIELD -> new YieldParameters(null, null);
            case SCHEMA_CONFORMANCE -> new SchemaConformanceParameters(null);
            case EXTRACTION_QUALITY -> new ExtractionQualityParameters(null, null);
            case BUSINESS_RULES -> new BusinessRulesParameters(null, null);
            case ERROR_LOAD -> new ErrorLoadParameters(null);
            case EVENT_CLASSIFICATION -> new EventClassificationParameters(null);
        };
    }
}
