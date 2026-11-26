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

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;

import java.util.ArrayList;
import java.util.List;

/// Holds a Learning Flow's check settings while the Setup tab edits them. It always has exactly one setting
/// for every check, in the order of [Check], and a setting is looked up and replaced by its check.
public final class CheckSet {

    private final List<CheckSetting> settings = new ArrayList<>();

    /// Replaces all the settings with a Learning Flow's.
    ///
    /// @param settings The Learning Flow's check settings, or null for none. A check they don't name is given
    ///                 its default setting.
    public void read(final List<CheckSetting> settings) {
        this.settings.clear();
        this.settings.addAll(CheckSettings.complete(settings));
    }

    /// @return A copy of every setting, in the order of [Check].
    public List<CheckSetting> write() {
        return new ArrayList<>(settings);
    }

    /// @return The settings to show for a person to change, which is all of them, in the order of [Check].
    public List<CheckSetting> shown() {
        return write();
    }

    /// Replaces a check's setting, keeping its position. Does nothing if the check has no setting.
    ///
    /// @param setting The new setting. Its check says which setting it replaces.
    public void replace(final CheckSetting setting) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).getCheck() == setting.getCheck()) {
                settings.set(i, setting);
                return;
            }
        }
    }

    /// @param check The check to look up.
    /// @return The check's current setting.
    public CheckSetting find(final Check check) {
        return CheckSettings.of(settings, check);
    }
}
