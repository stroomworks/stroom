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

import stroom.docref.HasDisplayValue;

/**
 * The texts of the flow's questions a document may override (design 01 §10.2): the system text, the four questions, and
 * the three passages of rules the questions share. Each names the variables it may use; the built-in text and
 * the rendering are the server's ({@code QuestionText}).
 */
public enum Template implements HasDisplayValue {
    SYSTEM("System text", "instructions", "demands"),
    CHAIN("Chain question", "headers", "elements", "sample", "feedback"),
    SPLIT("Split question", "headers", "elementType", "documentType", "splitRules", "sample", "feedback"),
    SPLIT_XML("Split question, XML input", "headers", "sample", "feedback"),
    SPLIT_JSON("Split question, JSON input", "headers", "sample", "feedback"),
    TARGET("Target question", "headers", "kind", "total", "transformationRules", "record", "feedback"),
    CONFIGURATION("Configuration question", "headers", "elementType", "documentType", "rules", "input", "split",
            "targets", "previous", "feedback"),
    ADMISSION("Admission question", "headers", "elementType", "documentType", "rules", "input", "split",
            "targets", "previous", "feedback", "marker", "admitted"),
    SPLIT_RULES("Split rules"),
    EXTRACTION_RULES("Extraction rules"),
    /// Task 7: the schema a translation must write is a setting on the Learning Flow, so the namespace,
    /// the `xsi:schemaLocation`, the version and the root element are variables here rather than words.
    TRANSFORMATION_RULES("Transformation rules",
            "targetNamespace", "targetSchemaLocation", "targetVersion", "targetRoot", "targetStructure"),
    /// What the target schema says a document of it holds (task 7, decision D2 option G). Its built-in
    /// text is event-logging's, written by hand; for any other schema it is generated from the XSD. A
    /// Learning Flow that overrides it says what it wants for its own schema, whichever that is.
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

    /**
     * The names a {@code ${name}} slot in this template may carry.
     */
    public String[] getVariables() {
        return variables;
    }
}
