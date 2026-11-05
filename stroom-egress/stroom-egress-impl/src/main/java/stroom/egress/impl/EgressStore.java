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

package stroom.egress.impl;

import stroom.egress.api.EgressRule;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMode;

import java.util.List;
import java.util.Map;

/// Where the rules and settings are kept. The guard reads them through [stroom.egress.api.EgressRuleSource] and
/// [stroom.egress.api.EgressSettings]; this is the side that the administrative interface writes.
public interface EgressStore {

    /// Every rule, enabled or not.
    ///
    /// @return The rules.
    List<EgressRule> listRules();

    /// Adds a rule, or replaces the one with the same id.
    ///
    /// @param rule The rule. A null id adds a new rule.
    /// @return The rule as stored, with its id.
    EgressRule saveRule(EgressRule rule);

    /// Deletes a rule.
    ///
    /// @param id The id.
    /// @return True if a rule was deleted.
    boolean deleteRule(long id);

    /// The mode of every kind.
    ///
    /// @return The modes.
    Map<EgressKind, EgressMode> getModes();

    /// The kill switch of every kind.
    ///
    /// @return Whether each kind is killed.
    Map<EgressKind, Boolean> getKillSwitches();

    /// Sets the mode of a kind.
    ///
    /// @param kind The kind.
    /// @param mode The mode.
    void setMode(EgressKind kind, EgressMode mode);

    /// Turns the kill switch of a kind on or off.
    ///
    /// @param kind   The kind.
    /// @param killed True to deny everything of that kind.
    void setKilled(EgressKind kind, boolean killed);
}
