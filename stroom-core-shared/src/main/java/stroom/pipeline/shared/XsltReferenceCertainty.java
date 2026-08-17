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

package stroom.pipeline.shared;

/**
 * Whether the parser had to reason about a value, or was simply handed it.
 * <p>
 * This is not a confidence score. Both values are certain; neither makes the reference more or less
 * likely to be real.
 * <p>
 * One wrinkle to know about, since it is Saxon's decision rather than ours: the XPath engine evaluates a
 * {@code concat()} of pure literals while compiling, so {@code concat('geo_', 'prod')} arrives already
 * folded and is reported {@link #STATIC}, even though the string {@code geo_prod} appears nowhere in the
 * source. Certainty therefore answers "did the parser have to work this out", not "can a reader find this
 * string in the file".
 */
public enum XsltReferenceCertainty {

    /**
     * The value was a literal by the time the parser saw it.
     */
    STATIC,

    /**
     * The parser worked the value out - by following a variable binding, taking a conditional branch, or
     * folding a string the XPath engine had left unfolded.
     */
    INFERRED,
}
