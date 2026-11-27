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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/// The settings a scorer needs beyond its state and threshold. There is one subclass for each scorer that takes
/// parameters; the others, such as the compile check, input coverage and schema conformance, take none (schema
/// conformance judges against the Learning Flow's target schema, which is a document setting). [#check()] says
/// which check a set of parameters belongs to, and [CheckSetting] refuses a mismatch.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = YieldParameters.class, name = "yield"),
        @JsonSubTypes.Type(value = ExtractionQualityParameters.class, name = "extractionQuality"),
        @JsonSubTypes.Type(value = BusinessRulesParameters.class, name = "businessRules"),
        @JsonSubTypes.Type(value = TimesParameters.class, name = "times")
})
@JsonInclude(Include.NON_NULL)
public abstract sealed class ScorerParameters
        permits YieldParameters,
        ExtractionQualityParameters,
        BusinessRulesParameters,
        TimesParameters {

    /// @return The check these parameters belong to.
    public abstract Check check();

    /// @param check The check being added to a Learning Flow document.
    /// @return The parameters the check starts with, or null for a check that takes none.
    public static ScorerParameters defaultsFor(final Check check) {
        return switch (check) {
            case YIELD -> new YieldParameters(null, null);
            case QUALITY -> new ExtractionQualityParameters(null, null);
            case RULES -> new BusinessRulesParameters(null, null);
            case TIMES -> new TimesParameters(null, null);
            default -> null;
        };
    }
}
