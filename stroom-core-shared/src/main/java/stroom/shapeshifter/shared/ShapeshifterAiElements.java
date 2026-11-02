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

package stroom.shapeshifter.shared;

/**
 * The pipeline element types a supervised stage can be (design 01 §3, §12 item 4), shared because the
 * client has to recognise them: the stepper shows a stage pane where these elements' code pane would be
 * (A30), and which elements those are is not something the stepper can be told at runtime.
 * <p>
 * One stage, two shapes. The parser is the extraction stage, fed the stream; the filter is the
 * transformation stage, fed the records a parser above it made. They differ only in where they can
 * stand.
 */
public final class ShapeshifterAiElements {

    /**
     * The extraction stage: sits where a parser sits.
     */
    public static final String PARSER = "ShapeshifterAi";

    /**
     * The transformation stage: sits where a filter sits, which a parser cannot.
     */

    private ShapeshifterAiElements() {
    }
}
