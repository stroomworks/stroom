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

package stroom.pipeline.factory;

import stroom.pipeline.shared.stepping.ElementStepDetails;

/**
 * An element with something to say about a step beyond the text it read and wrote (A30, design 01
 * §11.7).
 * <p>
 * The capture asks the element itself, as each record is captured, rather than being handed something
 * to carry: an element that has details has them for the record it has just processed, and nothing
 * between it and the capture would know when that is.
 */
public interface HasStepDetails {

    /**
     * What this element did with the record just captured, or null where it has nothing to add. Called
     * once per record, immediately after the record was processed.
     *
     * @param recordIndex Which record of the current part has just been processed, counted from zero.
     *                    An element that runs a chain of its own over the whole stream decides once and
     *                    works record by record, and this is what tells it which record is being asked
     *                    about; an element given one record at a time can ignore it.
     */
    ElementStepDetails getStepDetails(long recordIndex);
}
