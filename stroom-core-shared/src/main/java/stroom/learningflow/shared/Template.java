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

import stroom.docref.HasDisplayValue;

/// The prompt texts a Learning Flow document may override: the system text, the question for each kind of
/// step, and the shared passages of rules and target description that the questions include. Each names the
/// variables it may use. The built-in text and the rendering are on the server (`QuestionText`).
public enum Template implements HasDisplayValue {
    SYSTEM("System text", "instructions", "demands"),
    CHAIN("Chain question", "headers", "elements", "sample", "feedback"),
    SPLIT("Split question", "headers", "elementType", "documentType", "splitRules", "sample", "feedback"),
    SPLIT_XML("Split question, XML input", "headers", "sample", "feedback"),
    SPLIT_JSON("Split question, JSON input", "headers", "sample", "feedback"),
    /// `targetRecord` is the element one record becomes, e.g. `<Event>`.
    TARGET("Target question", "headers", "kind", "total", "transformationRules", "record", "feedback",
            "targetRecord"),
    CONFIGURATION("Configuration question", "headers", "elementType", "documentType", "rules", "input", "split",
            "targets", "previous", "feedback"),
    ADMISSION("Admission question", "headers", "elementType", "documentType", "rules", "input", "split",
            "targets", "previous", "feedback", "marker", "admitted"),
    SPLIT_RULES("Split rules"),
    EXTRACTION_RULES("Extraction rules"),
    /// The target schema is a setting on the Learning Flow document, so its namespace, `xsi:schemaLocation`,
    /// version and root element are variables here rather than fixed text. So are the element one record
    /// becomes, with its article (`aTargetRecord`, e.g. "an Event"), and the rule against event-logging's
    /// untyped `Data` and `Unknown` (`untypedRule`), which is empty for any other schema.
    TRANSFORMATION_RULES("Transformation rules",
            "targetNamespace", "targetSchemaLocation", "targetVersion", "targetRoot", "targetStructure",
            "aTargetRecord", "untypedRule"),
    /// A description of what a document in the target schema contains. The built-in text, written by hand,
    /// describes event-logging; for any other schema the description is generated from the XSD. A Learning
    /// Flow document can override it to describe its own schema in its own words.
    TARGET_STRUCTURE("Target structure");

    private final String displayValue;
    private final String[] variables;

    Template(final String displayValue, final String... variables) {
        this.displayValue = displayValue;
        this.variables = variables;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return The variable names that may appear in this template as `${name}` placeholders.
    public String[] getVariables() {
        return variables;
    }
}
