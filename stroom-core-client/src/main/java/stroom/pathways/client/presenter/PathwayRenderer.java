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

package stroom.pathways.client.presenter;

import com.google.gwt.safehtml.shared.SafeHtml;

/**
 * Draws a learnt model. One implementation per way of looking at it, chosen by the toolbar.
 *
 * <p>Two things every implementation owes the view around it, because everything but the drawing is
 * shared between them:
 *
 * <ul>
 *     <li>One outer element. It is what scrolls, and where the view was scrolled to is taken off it
 *     and put back when the model is redrawn.</li>
 *     <li>A {@code uuid} attribute on whatever element stands for a node. Clicking looks for the
 *     nearest parent carrying one, and picking the same node out again after the model is wound back
 *     searches on it. A node drawn without one cannot be selected.</li>
 * </ul>
 *
 * <p>The element carrying the uuid has a class put on and taken off it as it is selected, so it must
 * be an HTML element rather than an SVG one — {@code addClassName} does not work on SVG, where class
 * is an {@code SVGAnimatedString} rather than a string.
 */
interface PathwayRenderer {

    SafeHtml render(RenderRequest request);

    /**
     * Whether the drawing opens in the middle rather than at its top left. A drawing that puts the
     * root in the middle of the canvas shows nothing much in the corner the view would otherwise
     * start at.
     */
    boolean isCentred();
}
