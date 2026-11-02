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

package stroom.pipeline.stepping.client.component;

import stroom.pipeline.shared.stepping.ElementStepDetails;
import stroom.widget.component.client.HasControllerNode;

import com.google.gwt.user.client.ui.IsWidget;

/// A pane an element shows on the stepping screen in place of its code pane (A30): what the element had
/// to say about the step beyond its input and output, e.g. a supervised stage's decision. Made by the
/// element's controller ([SteppingElementController]), which owns it, once the element's panes are laid
/// out.
public interface ElementStepDetailsPane extends HasControllerNode, IsWidget {

    /// Shows the details of the step at the cursor; called once a step.
    ///
    /// @param details The element's details for the step, or `null` where it said nothing.
    void setDetails(ElementStepDetails details);
}
