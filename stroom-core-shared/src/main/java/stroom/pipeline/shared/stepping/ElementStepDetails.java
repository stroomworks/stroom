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

package stroom.pipeline.shared.stepping;

import stroom.learningflow.shared.LearningFlowStepDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/// Extra information an element reports about one step in the stepper, beyond the text it read and wrote.
///
/// Most elements have none, because their input, output and log tell the whole story, and this is null for
/// them. The Smart Pipeline element's behaviour is a decision (which learned translation served this stream,
/// and why), which has no single document to show as code and cannot be put in a log line without losing the
/// links and actions that go with it. Such an element puts a subtype of this on the step instead, and the
/// stepper shows a pane registered for that subtype in place of the code pane.
///
/// The subtypes are listed here because Jackson needs their names for serialisation. Any element with a
/// decision to explain can add a subtype.
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LearningFlowStepDetails.class, name = "learningFlow")
})
@JsonInclude(Include.NON_NULL)
public abstract class ElementStepDetails {

    /// The elements this one ran inside itself for the record being stepped, such as the elements of a nested
    /// pipeline, in the order it ran them.
    ///
    /// It is declared here rather than on a subtype because the stepper draws these elements, and it should
    /// not need to know what kind of element it is looking at to do so. Any element that runs other elements
    /// can return them here and be expanded in the same way.
    ///
    /// @return The nested elements, or an empty list where it ran none.
    @JsonIgnore
    public List<NestedElementData> getNested() {
        return List.of();
    }
}
