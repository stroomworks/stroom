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

package stroom.learningflow.shared;

import stroom.docref.HasDisplayValue;

import java.util.ArrayList;
import java.util.List;

/// The ready-made sets of check settings a Learning Flow document can choose from. *Standard* is what a new
/// document has: the objective checks as gates, the literals and time plausibility checks as advice, and the
/// heuristic checks (yield, extraction quality, business rules) off.
public enum CheckPreset implements HasDisplayValue {
    LENIENT("Lenient",
            "The events must compile, be valid against the event schema, account for every record read, keep what "
            + "the agreed targets need and do no worse on records accepted before. Reading all the input and "
            + "matching the targets are only advice."),
    STANDARD("Standard",
            "As Lenient, and the parser must read nearly all the input and the events must match the agreed "
            + "targets. Record counts, content and business rules aren't checked."),
    STRICT("Strict",
            "As Standard, with higher bars for reading the input and keeping records whole, the business rules as "
            + "a gate, and record counts and content as advice.");

    private final String displayValue;
    private final String description;

    CheckPreset(final String displayValue, final String description) {
        this.displayValue = displayValue;
        this.description = description;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return What it asks of a translation, in a sentence or two.
    public String getDescription() {
        return description;
    }

    /// @return One setting for every check, in the order of [Check].
    public List<CheckSetting> settings() {
        final List<CheckSetting> settings = new ArrayList<>();
        for (final Check check : Check.values()) {
            settings.add(setting(check));
        }
        return settings;
    }

    /// @param settings A Learning Flow's settings.
    /// @return The preset they are, or null for a set of the Learning Flow's own.
    public static CheckPreset of(final List<CheckSetting> settings) {
        final List<CheckSetting> complete = CheckSettings.complete(settings);
        for (final CheckPreset preset : values()) {
            if (preset.settings().equals(complete)) {
                return preset;
            }
        }
        return null;
    }

    private CheckSetting setting(final Check check) {
        return switch (check) {
            // Completeness is a gate in every preset: a record that silently never became an event is exactly
            // the fault it exists to catch, and a translation that legitimately makes fewer events says so
            // with a declared skip
            case COMPILE, PERMITTED, CONFORMANCE, COMPLETENESS, PRESERVATION, REGRESSION -> CheckSetting.of(
                    check, CheckState.GATE);
            case COVERAGE -> new CheckSetting(check, this == LENIENT
                    ? CheckState.ADVICE
                    : CheckState.GATE, threshold(0.8, 0.9, 0.95), null);
            case WHOLENESS -> new CheckSetting(check, CheckState.GATE, threshold(0.8, 0.9, 0.95), null);
            case FIDELITY -> CheckSetting.of(check, this == LENIENT
                    ? CheckState.ADVICE
                    : CheckState.GATE);
            case YIELD -> new CheckSetting(check, this == STRICT
                    ? CheckState.ADVICE
                    : CheckState.OFF, threshold(0.5, 0.5, 0.8), null);
            case QUALITY -> new CheckSetting(check, this == STRICT
                    ? CheckState.ADVICE
                    : CheckState.OFF, threshold(0.7, 0.7, 0.9), null);
            case RULES -> CheckSetting.of(check, this == STRICT
                    ? CheckState.GATE
                    : CheckState.OFF);
            // Advice except under Strict: it shows beside a draft and goes back to the model, but the check is
            // new, so it does not refuse a candidate until it has proved on real feeds that it does not give
            // false failures. Strict gates it.
            case TIMES -> CheckSetting.of(check, this == STRICT
                    ? CheckState.GATE
                    : CheckState.ADVICE);
            // Advice except under Strict, for the same reason as the times check: a translation that writes
            // this stream's year or environment into itself is wrong for the next stream, but the check is new
            // and has not yet proved it gives no false failures
            case LITERALS -> CheckSetting.of(check, this == STRICT
                    ? CheckState.GATE
                    : CheckState.ADVICE);
        };
    }

    private double threshold(final double lenient, final double standard, final double strict) {
        return switch (this) {
            case LENIENT -> lenient;
            case STANDARD -> standard;
            case STRICT -> strict;
        };
    }
}
