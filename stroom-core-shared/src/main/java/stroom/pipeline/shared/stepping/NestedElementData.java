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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One element of a pipeline an element ran <em>inside</em> itself, and what it made of the record being
 * stepped (A30, design 01 §11.7).
 * <p>
 * Some elements are not one step but several. A supervised stage runs a whole learned chain — a parser
 * and a transform, say — over the record it is given and emits what came out the far end, so selecting
 * it in the stepper shows a stream going in and events coming out with everything that decided the
 * shape of them hidden in between. A person debugging that needs the same thing they get anywhere else
 * in a pipeline: each element, what it was given, what it wrote.
 * <p>
 * These are not elements of the pipeline being stepped. They are not in its structure, they cannot be
 * selected for a filter or edited, and they exist only for as long as whatever ran them was bound to
 * run them — which for a supervised stage is until the next thing is learned. The stepper shows them
 * beneath the element that ran them and leaves the pipeline alone.
 * <p>
 * The id is namespaced by the element that ran them, because a fragment may hold an {@code XSLTFilter}
 * and so may the pipeline it is running inside.
 */
@JsonInclude(Include.NON_NULL)
public class NestedElementData {

    @JsonProperty
    private final String id;
    @JsonProperty
    private final String name;
    @JsonProperty
    private final String type;
    @JsonProperty
    private final String input;
    @JsonProperty
    private final String output;
    @JsonProperty
    private final boolean formatInput;
    @JsonProperty
    private final boolean formatOutput;
    @JsonProperty
    private final boolean wholeStream;
    @JsonProperty
    private final boolean truncated;

    @JsonCreator
    public NestedElementData(@JsonProperty("id") final String id,
                             @JsonProperty("name") final String name,
                             @JsonProperty("type") final String type,
                             @JsonProperty("input") final String input,
                             @JsonProperty("output") final String output,
                             @JsonProperty("formatInput") final boolean formatInput,
                             @JsonProperty("formatOutput") final boolean formatOutput,
                             @JsonProperty("wholeStream") final boolean wholeStream,
                             @JsonProperty("truncated") final boolean truncated) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.input = input;
        this.output = output;
        this.formatInput = formatInput;
        this.formatOutput = formatOutput;
        this.wholeStream = wholeStream;
        this.truncated = truncated;
    }

    /**
     * The id the stepper knows this element by, namespaced by the element that ran it.
     */
    public String getId() {
        return id;
    }

    /**
     * What the element is called inside whatever ran it, which is what the tree shows.
     */
    public String getName() {
        return name;
    }

    /**
     * The pipeline element type, so that the tree can draw it with its own icon.
     */
    public String getType() {
        return type;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public boolean isFormatInput() {
        return formatInput;
    }

    public boolean isFormatOutput() {
        return formatOutput;
    }

    /**
     * Whether this is what the element made of the <em>whole stream</em> rather than of the record at
     * the cursor.
     * <p>
     * A chain is run over everything the element that ran it was given, and cuts the records itself. A
     * supervised stage standing where a parser stands is given the stream, so its chain runs once: the
     * learned parser reads the whole of it and the learned transform is handed the whole of what that
     * produced, and the records the stepper walks are cut from the far end afterwards. Stepping forward
     * moves the stage's record on and does not move these, because there is nothing to move — one run
     * did all of it.
     * <p>
     * False where the chain ran for this record alone: a stage standing where a filter stands is given
     * one record at a time, and so is its chain.
     * <p>
     * The pane says which, because showing six records' worth of output while the cursor sits on the
     * first would otherwise read as a fault.
     */
    public boolean isWholeStream() {
        return wholeStream;
    }

    /**
     * Whether the text held here is the beginning of what the element read and wrote rather than all of
     * it.
     * <p>
     * A chain run once over the whole stream is shown against <em>every</em> record of that stream, and
     * what it read and wrote is the whole stream's — so carrying all of it would write the stream into
     * the step store once per record of the stream, which for a large one exhausts the store outright.
     * An excerpt shows the shape of what the element made, says that it is an excerpt, and leaves the
     * whole of it where it has always been: in the fragment, which the stage pane links to and which
     * steps like any other pipeline.
     * <p>
     * False for a chain that ran for this record alone, whose text is one record's and is carried whole.
     */
    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }
}
