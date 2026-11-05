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

/// Reads and writes the egress rules and per-kind settings in the database. The registry keeps a copy in memory,
/// so this is not on the path of a request that is being checked.
public interface EgressDao {

    /// Every rule, enabled or not, in the order they were added.
    ///
    /// @return The rules.
    List<EgressRule> fetchRules();

    /// Adds a rule, or replaces the one with the same id.
    ///
    /// @param rule The rule. A null id adds a new rule.
    /// @param user Who made the change, for the audit columns.
    /// @return The rule as stored, with its id.
    EgressRule saveRule(EgressRule rule, String user);

    /// Deletes a rule.
    ///
    /// @param id The id.
    /// @return True if a rule was deleted.
    boolean deleteRule(long id);

    /// The mode of each kind that has one stored.
    ///
    /// @return The modes. A kind with none stored is absent.
    Map<EgressKind, EgressMode> fetchModes();

    /// The kill switch of each kind that has one stored.
    ///
    /// @return The kill switches. A kind with none stored is absent.
    Map<EgressKind, Boolean> fetchKillSwitches();

    /// Sets the mode of a kind.
    ///
    /// @param kind The kind.
    /// @param mode The mode.
    /// @param user Who made the change.
    void saveMode(EgressKind kind, EgressMode mode, String user);

    /// Turns the kill switch of a kind on or off.
    ///
    /// @param kind   The kind.
    /// @param killed True to deny everything of that kind.
    /// @param user   Who made the change.
    void saveKilled(EgressKind kind, boolean killed, String user);
}
