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

package stroom.learningflow.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/// The reply to a request to improve a serving binding, as one sentence for the person who asked.
///
/// The improvement session itself runs later on the background learning worker, so the reply cannot say how
/// it ended. It says either that the improvement was asked for, or why it was refused straight away (for
/// example, because the binding is a draft or learning is disabled). The session shows on the Learning Status
/// tab like any other.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImproveOutcome {

    @JsonProperty
    private final String said;

    @JsonCreator
    public ImproveOutcome(@JsonProperty("said") final String said) {
        this.said = said;
    }

    /// @return One sentence for the person who asked: that the improvement was asked for and where its
    /// progress will show, or why it was refused.
    public String getSaid() {
        return said;
    }
}
