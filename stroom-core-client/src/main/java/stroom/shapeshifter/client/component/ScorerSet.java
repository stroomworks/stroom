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

import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;

import java.util.ArrayList;
import java.util.List;

/// The scorer set of design §8.4 as the Score tab holds it while it is edited: a Shapeshifter AI
/// document applies each scorer at most once, so the set is keyed by scorer type, and an editor
/// refuses a type already in it.
///
/// A scorer's editor isn't modal (an editor of an item in a list), so the set may change while one
/// is open: a scorer is found again by its type when its editor's OK is pressed.
public final class ScorerSet {

    private final List<ScorerSetting> scorers = new ArrayList<>();

    /// Replaces the set.
    ///
    /// @param scorers The scorers, in the order to show them; null for none.
    public void read(final List<ScorerSetting> scorers) {
        this.scorers.clear();
        if (scorers != null) {
            this.scorers.addAll(scorers);
        }
    }

    /// @return A copy of the scorers, in their order.
    public List<ScorerSetting> write() {
        return new ArrayList<>(scorers);
    }

    /// @return The first type with a scorer, in the order the types are declared, that no scorer in the
    /// set has, or null when every such scorer is in the set.
    public ScorerType firstUnusedType() {
        for (final ScorerType type : ScorerType.values()) {
            if (type.isBuilt() && find(type) == null) {
                return type;
            }
        }
        return null;
    }

    /// @param type      The type a scorer is to have.
    /// @param replacing The type of the scorer being edited, whose own type is not a duplicate of
    ///                  itself; null when adding.
    /// @return Why the type can't be had (another scorer in the set has it), or null if it can.
    public String duplicate(final ScorerType type, final ScorerType replacing) {
        if (type != null && type != replacing && find(type) != null) {
            return "The " + type.getDisplayValue() + " scorer is already in the set.";
        }
        return null;
    }

    /// Adds a scorer at the end.
    ///
    /// @param scorer The scorer.
    public void add(final ScorerSetting scorer) {
        scorers.add(scorer);
    }

    /// Replaces the scorer of a type, where it was.
    ///
    /// @param type   The type of the scorer to replace.
    /// @param scorer What replaces it, which may be of another type.
    /// @return False if no scorer has the type any more (it was removed), when nothing changes.
    public boolean replace(final ScorerType type, final ScorerSetting scorer) {
        final ScorerSetting existing = find(type);
        if (existing == null) {
            return false;
        }
        scorers.set(scorers.indexOf(existing), scorer);
        return true;
    }

    /// Removes the scorer of a type, if there is one.
    ///
    /// @param type The type.
    public void remove(final ScorerType type) {
        scorers.removeIf(scorer -> scorer.getType() == type);
    }

    /// @param type The type.
    /// @return The scorer of the type in the set, or null if there is none.
    public ScorerSetting find(final ScorerType type) {
        for (final ScorerSetting scorer : scorers) {
            if (scorer.getType() == type) {
                return scorer;
            }
        }
        return null;
    }
}
