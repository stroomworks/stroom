/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.element;

import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.ModelNotChosenException;
import stroom.learningflow.learning.Question;

import java.util.List;

/// The [Advisor] for a Learning Flow document that names no model. Asking it throws a
/// [ModelNotChosenException], so an attempt that needs the model stops with a clear reason rather than
/// pretending to learn. A document whose learning mode is `DISABLED` never asks it.
public class NoModelAdvisor implements Advisor {

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        throw new ModelNotChosenException("The Learning Flow names no model to ask. Choose one on its "
                                        + "Setup tab, or switch learning off there.");
    }
}
