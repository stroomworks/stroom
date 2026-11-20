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
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// Which of the Setup tab's sections ([SetupSection]) hold something under "Advanced options" that is not as
/// a new Learning Flow has it. Those open of their own accord, so that nothing changed is ever out of sight;
/// the rest stay folded until someone opens them.
///
/// What each section keeps under Advanced options:
///
/// | Section | Advanced options |
/// |---|---|
/// | Learning steps | tries per step, largest sample, the question text every step shares |
/// | Quality checks | the checks themselves where they are no starting set, lines kept back |
/// | Approving new translations | records before a translation is trusted, the fallback |
/// | Monitoring translations in use | records kept to check against, when a waiting stream is overdue |
///
/// Percentages are compared as the tab shows them, whole, so that a fraction read back is not taken for a
/// change.
public final class AdvancedSettings {

    private static final ShapeshifterAiDoc NEW = ShapeshifterAiDoc.builder().uuid("new").build();

    private AdvancedSettings() {
        // Static utility
    }

    /// @param doc A Learning Flow, as the Setup tab has it now.
    /// @return The sections whose Advanced options hold something other than a new Learning Flow's.
    public static Set<SetupSection> changed(final ShapeshifterAiDoc doc) {
        final Set<SetupSection> changed = EnumSet.noneOf(SetupSection.class);
        if (doc.getMaxAttempts() != NEW.getMaxAttempts()
            || doc.getSampleSizeLimit() != NEW.getSampleSizeLimit()
            || ownSharedTexts(doc)) {
            changed.add(SetupSection.APPROACH);
        }
        if (ownChecks(doc.getScorers())
            || !samePercent(doc.getHeldOutFraction(), NEW.getHeldOutFraction())) {
            changed.add(SetupSection.QUALITY);
        }
        if (doc.getMinRecordsToLearn() != NEW.getMinRecordsToLearn()
            || hasFallback(doc) != hasFallback(NEW)) {
            changed.add(SetupSection.GOING_LIVE);
        }
        if (doc.getRegressionCap() != NEW.getRegressionCap()
            || doc.getMaxDeferralAgeMs() != NEW.getMaxDeferralAgeMs()) {
            changed.add(SetupSection.IN_SERVICE);
        }
        return changed;
    }

    // Checks that are no starting set. None at all is not: the readiness check offers the standard set, and
    // the starting sets are always in sight
    private static boolean ownChecks(final List<ScorerSetting> scorers) {
        return scorers != null && !scorers.isEmpty() && QualityPreset.of(scorers) == null;
    }

    // Any of the question texts every step shares that the document has its own words for, rather than the
    // built-in's
    private static boolean ownSharedTexts(final ShapeshifterAiDoc doc) {
        return doc.getFlow().getTemplates().keySet().stream().anyMatch(LearnFlow.SHARED_TEXTS::contains);
    }

    private static boolean samePercent(final double a, final double b) {
        return ScoreValues.toPercent(a) == ScoreValues.toPercent(b);
    }

    private static boolean hasFallback(final ShapeshifterAiDoc doc) {
        return ApproachFlow.hasFallback(doc.getFlow().getSteps());
    }
}
