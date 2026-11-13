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

package stroom.shapeshifter.client.component;

import stroom.docref.HasDisplayValue;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;

import java.util.List;

/// A starting set of quality checks the Setup tab offers, so that a document need not be built check by
/// check, and none is left judging nothing (ISSUES item 22). Choosing one writes an ordinary list of scorer
/// settings, with each scorer's default parameters; editing any check afterwards makes the set the
/// document's own. Standard is the set the demonstration content was learned under.
public enum QualityPreset implements HasDisplayValue {
    LENIENT("Lenient",
            "The events must compile and be valid against the event schema. Thin events and odd record counts "
            + "are allowed.",
            List.of(new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                    new ScorerSetting(ScorerType.YIELD, 1.0, 0.3, false, null),
                    new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true, null),
                    new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.5, false, null))),
    STANDARD("Standard",
            "The events must compile, be valid against the event schema and carry real content, from about the "
            + "right number of records.",
            List.of(new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                    new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false, null),
                    new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true, null),
                    new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true, null))),
    STRICT("Strict",
            "As Standard, and the parser must also read all the input, every check must pass, and the bar for "
            + "content is higher.",
            List.of(new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                    new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.95, true, null),
                    new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, true, null),
                    new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true, null),
                    new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.9, true, null)));

    private final String displayValue;
    private final String description;
    private final List<ScorerSetting> settings;

    QualityPreset(final String displayValue, final String description, final List<ScorerSetting> settings) {
        this.displayValue = displayValue;
        this.description = description;
        this.settings = settings;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return What it asks of a translation, in a sentence.
    public String getDescription() {
        return description;
    }

    /// @return Its checks, in the order to show them.
    public List<ScorerSetting> getSettings() {
        return settings;
    }

    /// @param settings A document's checks, or null for none.
    /// @return The preset whose checks they are exactly, or null where they are the document's own.
    public static QualityPreset of(final List<ScorerSetting> settings) {
        if (settings == null) {
            return null;
        }
        for (final QualityPreset preset : values()) {
            if (preset.settings.equals(settings)) {
                return preset;
            }
        }
        return null;
    }
}
