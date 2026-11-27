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

package stroom.learningflow.client.component;

import java.util.Objects;

/// Identifies one stop on the Setup tab's route map ([RouteMap]), and therefore what the tab shows beside the
/// map. A stop is either one of the tab's sections ([SetupSection]) or one step of the learning flow,
/// identified by its index.
///
/// A stop's key ([#key()]) is written into the map's markup, and is parsed back ([#parse]) when the user
/// chooses a stop.
public final class SetupStop {

    private static final String SECTION = "section:";
    private static final String STEP = "step:";

    private final SetupSection section;
    private final int step;

    private SetupStop(final SetupSection section, final int step) {
        this.section = section;
        this.step = step;
    }

    /// @param section A section of the tab.
    /// @return The stop for that section.
    public static SetupStop of(final SetupSection section) {
        return new SetupStop(Objects.requireNonNull(section, "section"), -1);
    }

    /// @param index A step's index in the flow, counted from zero.
    /// @return The stop for that step.
    public static SetupStop step(final int index) {
        if (index < 0) {
            throw new IllegalArgumentException("A step's index can't be negative: " + index);
        }
        return new SetupStop(null, index);
    }

    /// Parses a stop from a key read from the map's markup.
    ///
    /// @param key A stop's key ([#key()]), or null.
    /// @return The stop, or null if the key is null or not a valid stop key.
    public static SetupStop parse(final String key) {
        if (key == null) {
            return null;
        }
        try {
            if (key.startsWith(SECTION)) {
                return of(SetupSection.valueOf(key.substring(SECTION.length())));
            }
            if (key.startsWith(STEP)) {
                return step(Integer.parseInt(key.substring(STEP.length())));
            }
        } catch (final IllegalArgumentException e) {
            // Not a valid key. This also catches NumberFormatException, which is a subclass.
            return null;
        }
        return null;
    }

    /// @return True if this stop is a step of the flow, false if it is a section.
    public boolean isStep() {
        return section == null;
    }

    /// @return The section, or null if this stop is a step.
    public SetupSection getSection() {
        return section;
    }

    /// @return The step's index, or -1 if this stop is a section.
    public int getStep() {
        return step;
    }

    /// @return The stop's key in the map's markup, e.g. "section:QUALITY" or "step:2".
    public String key() {
        return section == null
                ? STEP + step
                : SECTION + section.name();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SetupStop that = (SetupStop) o;
        return step == that.step && section == that.section;
    }

    @Override
    public int hashCode() {
        return Objects.hash(section, step);
    }

    @Override
    public String toString() {
        return key();
    }
}
