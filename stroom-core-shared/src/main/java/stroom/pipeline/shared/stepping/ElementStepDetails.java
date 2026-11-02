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

import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * What one element has to say about a step, beyond the text it read and wrote (A30, design 01 §11.7).
 * <p>
 * Most elements have nothing: their input, output and log are the whole story, and this is null for
 * them. An element whose behaviour is a <em>decision</em> — which fragment this stream was routed to,
 * and why — has no single document to show as code and cannot say it in a log line without losing the
 * links and the actions that go with it. It puts a subtype of this on the step instead, and the stepper
 * gives it to a presenter registered for the type in place of the code pane.
 * <p>
 * The subtypes are named here because Jackson needs them by name on the wire. That is a registration
 * list and not a hierarchy: the slot is for any element with a decision to explain.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ShapeshifterAiStepDetails.class, name = "shapeshifterAi")
})
@JsonInclude(Include.NON_NULL)
public abstract class ElementStepDetails {

    /**
     * The elements this one ran inside itself for the record being stepped, in the order it ran them, or
     * empty where it ran none (A30, design 01 §11.7).
     * <p>
     * Declared here rather than on a subtype because the stepper draws them, and the stepper must not
     * have to know what kind of element it is looking at to do so. Any element that is not one step but
     * several can answer this and be expanded the same way.
     */
    @JsonIgnore
    public List<NestedElementData> getNested() {
        return List.of();
    }
}
