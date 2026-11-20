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

import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckSettings;

import java.util.ArrayList;
import java.util.List;

/// A Learning Flow's checks as the Setup tab holds them while they are edited (the one model of checks):
/// one setting for every check, in the order of [Check], found and replaced by its check.
public final class CheckSet {

    private final List<CheckSetting> settings = new ArrayList<>();

    /// Replaces the set.
    ///
    /// @param settings The Learning Flow's checks; any it doesn't name have their defaults; null for none.
    public void read(final List<CheckSetting> settings) {
        this.settings.clear();
        this.settings.addAll(CheckSettings.complete(settings));
    }

    /// @return A copy of every setting, in the order of [Check].
    public List<CheckSetting> write() {
        return new ArrayList<>(settings);
    }

    /// @return The settings a person can change: those of the checks that are built, in the order of
    ///         [Check]. One that isn't built can only be off, so it isn't shown.
    public List<CheckSetting> shown() {
        final List<CheckSetting> shown = new ArrayList<>();
        for (final CheckSetting setting : settings) {
            if (setting.getCheck().isBuilt()) {
                shown.add(setting);
            }
        }
        return shown;
    }

    /// Replaces a check's setting, where it was.
    ///
    /// @param setting The new setting, whose check says which it replaces.
    public void replace(final CheckSetting setting) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).getCheck() == setting.getCheck()) {
                settings.set(i, setting);
                return;
            }
        }
    }

    /// @param check A check.
    /// @return Its setting.
    public CheckSetting find(final Check check) {
        return CheckSettings.of(settings, check);
    }
}
