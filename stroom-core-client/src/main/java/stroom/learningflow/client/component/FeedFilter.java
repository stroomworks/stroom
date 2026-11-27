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

import stroom.docref.HasDisplayValue;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.SupervisorLearner;

/// The choices for which Smart Pipelines a list of them shows, according to the state of each one's learner.
/// Used on a Learning Flow document's Pipelines tab and on the Smart Pipelines screen.
public enum FeedFilter implements HasDisplayValue {
    /// Every feed.
    ALL("All pipelines"),
    /// Waiting for a person: a draft to approve, or a paused run.
    NEEDS_YOU("Needs you"),
    /// Learning: a run is under way, or nothing translates the feed yet.
    LEARNING("Learning"),
    /// A translation is in use.
    TRANSLATING("Translating"),
    /// Learning has been stopped, or is switched off.
    STOPPED("Stopped or off");

    private final String displayValue;

    FeedFilter(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// Whether a list filtered by this differs from the unfiltered list, by leaving feeds out or by including
    /// deleted pipelines. The filter button uses this to show that a filter is on, so that a person is not
    /// left wondering where the other feeds went.
    ///
    /// @param showDeleted Whether feeds whose pipeline was deleted are listed too.
    /// @return False only when the list shows every feed of every pipeline that still exists.
    public boolean isFiltering(final boolean showDeleted) {
        return this != ALL || showDeleted;
    }

    /// @param learner A feed's learner row.
    /// @return Whether this filter includes the feed.
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
