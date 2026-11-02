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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.shared.ShapeshifterAiDoc;

/**
 * Where a stage gets its {@link Advisor}: one per document, since the document names the model to ask
 * (design 01 §10) and the instructions every question carries. A node answers with the model the
 * document names; a scenario answers with its script whatever the document.
 */
public interface Advisors {

    Advisor of(ShapeshifterAiDoc doc);
}
