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

import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.LearningFlowDoc;

import java.util.EnumSet;
import java.util.Set;

/// Works out which sections of the Setup tab ([SetupSection]) have a setting under "Advanced options" that
/// differs from a new Learning Flow's. The Setup tab opens those sections' advanced options automatically,
/// so that a changed setting is never hidden; the others stay collapsed until someone opens them.
///
/// What each section keeps under Advanced options:
///
/// | Section | Advanced options |
/// |---|---|
/// | Learning steps | tries per step, largest sample |
/// | Quality checks | the checks themselves, when they match none of the preset check sets; lines held out |
/// | Approving new translations | records needed before a translation is trusted, the fallback |
/// | Monitoring translations in use | records kept to check against, when a waiting stream is overdue |
///
/// Fractions are compared as the whole percentages the tab shows, so that a value that has been rounded
/// on its way back from the screen is not taken for a change.
public final class AdvancedSettings {

    private static final LearningFlowDoc NEW = LearningFlowDoc.builder().uuid("new").build();

    private AdvancedSettings() {
        // Static utility
    }

    /// @param doc A Learning Flow, as currently edited in the Setup tab.
    /// @return The sections whose Advanced options hold a setting that differs from a new Learning Flow's.
    public static Set<SetupSection> changed(final LearningFlowDoc doc) {
        final Set<SetupSection> changed = EnumSet.noneOf(SetupSection.class);
        if (doc.getMaxAttempts() != NEW.getMaxAttempts()
            || doc.getSampleSizeLimit() != NEW.getSampleSizeLimit()) {
            changed.add(SetupSection.APPROACH);
        }
        if (CheckPreset.of(doc.getChecks()) == null
            || !samePercent(doc.getHeldOutFraction(), NEW.getHeldOutFraction())) {
            changed.add(SetupSection.QUALITY);
        }
        if (doc.getMinRecordsToLearn() != NEW.getMinRecordsToLearn() || fallbackTurnedOff(doc)) {
            changed.add(SetupSection.GOING_LIVE);
        }
        if (doc.getRegressionCap() != NEW.getRegressionCap()
            || doc.getMaxDeferralAgeMs() != NEW.getMaxDeferralAgeMs()) {
            changed.add(SetupSection.IN_SERVICE);
        }
        return changed;
    }

    private static boolean samePercent(final double a, final double b) {
        return ScoreValues.toPercent(a) == ScoreValues.toPercent(b);
    }

    /// Whether the fallback has been turned off. Every built-in setup has the fallback on, so this is a change.
    ///
    /// The document is checked directly rather than compared with [#NEW]. A document built in the client has
    /// no steps (the server gives a new document its default steps when it creates it), so [#NEW] has no
    /// fallback either, and comparing against it would mark every Learning Flow as having changed this.
    /// A flow with no steps at all is not treated as having the fallback turned off; the Learning steps
    /// section already reports that it has no steps.
    ///
    /// @param doc The document being edited.
    /// @return Whether it has steps and none of them is the fallback.
    private static boolean fallbackTurnedOff(final LearningFlowDoc doc) {
        return !doc.getFlow().getSteps().isEmpty() && !ApproachFlow.hasFallback(doc.getFlow().getSteps());
    }
}
