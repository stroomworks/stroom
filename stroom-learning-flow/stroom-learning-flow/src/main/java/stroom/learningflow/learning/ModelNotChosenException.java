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

package stroom.learningflow.learning;

/// Thrown when a learning run needs to ask the model but the Learning Flow document names none. It has its
/// own type so that the stop is reported as "no model chosen" ([StopReports#failure]) rather than as an
/// unknown failure.
public class ModelNotChosenException extends IllegalStateException {

    /// @param message What is wrong, in a sentence.
    public ModelNotChosenException(final String message) {
        super(message);
    }
}
