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

import stroom.docref.HasDisplayValue;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.SupervisorLearner;

/// Which feeds the Feeds tab lists, by where they stand.
public enum FeedFilter implements HasDisplayValue {
    /// Every feed.
    ALL("All pipelines"),
    /// A draft to approve, or a paused run.
    NEEDS_YOU("Needs you"),
    /// A run under way, or nothing translating yet.
    LEARNING("Learning"),
    /// A translation in use.
    TRANSLATING("Translating"),
    /// Stopped learning, or learning switched off.
    STOPPED("Stopped or off");

    private final String displayValue;

    FeedFilter(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// Whether a list filtered by this leaves anything out, or lets deleted pipelines in, so that the filter
    /// button can say a filter is on rather than leaving a person wondering where the rest went.
    ///
    /// @param showDeleted Whether feeds whose pipeline was deleted are listed too.
    /// @return Whether it is anything but every feed of every pipeline that still exists.
    public boolean isFiltering(final boolean showDeleted) {
        return this != ALL || showDeleted;
    }

    /// @param learner A feed's learner row.
    /// @return Whether the filter lists it.
    public boolean test(final SupervisorLearner learner) {
        final LearnerState state = learner.getState();
        switch (this) {
            case NEEDS_YOU:
                return state == LearnerState.AWAITING_PERSON;
            case LEARNING:
                return state == LearnerState.LEARNING;
            case TRANSLATING:
                return state == LearnerState.SERVING;
            case STOPPED:
                return state == LearnerState.GIVEN_UP || state == LearnerState.LEARNING_OFF;
            default:
                return true;
        }
    }
}
