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

package stroom.pipelinefactory.impl;

/// A factory could not make a pipeline for a combination, and says why in a person's words.
public class PipelineMakerException extends RuntimeException {

    /// @param message Why, in a person's words.
    public PipelineMakerException(final String message) {
        super(message);
    }

    /// @param message Why, in a person's words.
    /// @param cause   What failed.
    public PipelineMakerException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
