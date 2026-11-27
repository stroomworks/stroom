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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// Utilities for a Learning Flow document's check settings as a whole. Every check has exactly one setting;
/// this fills in missing ones and validates a set.
public final class CheckSettings {

    private CheckSettings() {
        // Static utility
    }

    /// @return The settings a new Learning Flow has: the *Standard* preset.
    public static List<CheckSetting> defaults() {
        return CheckPreset.STANDARD.settings();
    }

    /// One setting for every check, in the order of [Check]: a check the list doesn't name has its
    /// default (*Standard*'s), and where a check is named twice the first is kept, for [#problems] to report.
    ///
    /// @param settings Settings, possibly not naming every check; null for none.
    /// @return A setting for every check.
    public static List<CheckSetting> complete(final List<CheckSetting> settings) {
        final Map<Check, CheckSetting> named = new EnumMap<>(Check.class);
        if (settings != null) {
            for (final CheckSetting setting : settings) {
                named.putIfAbsent(setting.getCheck(), setting);
            }
        }
        final List<CheckSetting> complete = new ArrayList<>();
        for (final CheckSetting setting : defaults()) {
            complete.add(named.getOrDefault(setting.getCheck(), setting));
        }
        return complete;
    }

    /// What is wrong with a set of settings, so that a document holding them is refused when it is saved
    /// or imported: a check named twice, the compile gate not a gate, the permitted check not a gate, or
    /// a threshold outside 0 to 1.
    ///
    /// @param settings The settings as they are held; null for none.
    /// @return The problems, as sentences; empty where there are none.
    public static List<String> problems(final List<CheckSetting> settings) {
        final List<String> problems = new ArrayList<>();
        if (settings == null) {
            return problems;
        }
        final Map<Check, Boolean> seen = new EnumMap<>(Check.class);
        for (final CheckSetting setting : settings) {
            final Check check = setting.getCheck();
            final String name = check.getDisplayValue();
            if (seen.put(check, Boolean.TRUE) != null) {
                problems.add("The " + name + " check is set more than once.");
            }
            if (check == Check.COMPILE && !setting.isGate()) {
                problems.add("The compile check is always a gate.");
            }
            // What a learned stylesheet may use is a safety check, not a quality one. A Learning Flow
            // cannot turn it down or off, because under automatic promotion nobody reads the stylesheet
            // before it runs
            if (check == Check.PERMITTED && !setting.isGate()) {
                problems.add("The permitted check is always a gate.");
            }
            final Double threshold = setting.getThreshold();
            if (threshold != null && (threshold < 0.0 || threshold > 1.0)) {
                problems.add("The " + name + " check's threshold must be between 0 and 1.");
            }
        }
        return problems;
    }

    /// @param settings A setting for every check.
    /// @param check    A check.
    /// @return That check's setting.
    public static CheckSetting of(final List<CheckSetting> settings, final Check check) {
        for (final CheckSetting setting : settings) {
            if (setting.getCheck() == check) {
                return setting;
            }
        }
        throw new IllegalArgumentException("No setting for the " + check.getDisplayValue() + " check");
    }
}
