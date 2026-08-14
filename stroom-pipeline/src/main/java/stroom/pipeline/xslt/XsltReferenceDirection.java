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

package stroom.pipeline.xslt;

/**
 * Which way data flows over an external endpoint, from Stroom's point of view.
 * <p>
 * Determined by the function, not by the URL: {@code stroom:http-call} issues a POST with a body and
 * so is {@link #OUT}, while {@code stroom:fetch-json} issues a GET and so is {@link #IN}.
 */
public enum XsltReferenceDirection {

    /**
     * Data leaves Stroom, e.g. {@code stroom:http-call}.
     */
    OUT,

    /**
     * Data enters Stroom, e.g. {@code stroom:fetch-json}.
     */
    IN,
}
