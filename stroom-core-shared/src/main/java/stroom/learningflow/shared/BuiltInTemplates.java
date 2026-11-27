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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;
import java.util.Objects;

/// The built-in text of every prompt template, with its version, as the server sends it to the Setup tab. It is
/// sent so that a person writing an override starts from the text it replaces.
@JsonPropertyOrder({"version", "templates"})
@JsonInclude(Include.NON_NULL)
public class BuiltInTemplates {

    @JsonProperty
    private final int version;
    @JsonProperty
    private final Map<Template, String> templates;

    @JsonCreator
    public BuiltInTemplates(@JsonProperty("version") final Integer version,
                            @JsonProperty("templates") final Map<Template, String> templates) {
        this.version = Objects.requireNonNullElse(version, 0);
        this.templates = templates;
    }

    public int getVersion() {
        return version;
    }

    public Map<Template, String> getTemplates() {
        return templates;
    }
}
