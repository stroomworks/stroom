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

import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;

import java.util.Map;

/**
 * What a drawing is made from: the model to show, where to put things, and what is known about how
 * each node has changed.
 */
class RenderRequest {

    private final Pathway pathway;
    private final PathNode layout;
    private final Map<String, NodeChange> changes;
    private final Map<String, NodeUsage> usage;
    private final long asAt;
    private final long changeCeiling;
    private final long usageCeiling;

    RenderRequest(final Pathway pathway,
                  final PathNode layout,
                  final Map<String, NodeChange> changes,
                  final Map<String, NodeUsage> usage,
                  final long asAt,
                  final long changeCeiling,
                  final long usageCeiling) {
        this.pathway = pathway;
        this.layout = layout;
        this.changes = changes;
        this.usage = usage;
        this.asAt = asAt;
        this.changeCeiling = changeCeiling;
        this.usageCeiling = usageCeiling;
    }

    /**
     * The model to show.
     */
    Pathway getPathway() {
        return pathway;
    }

    /**
     * Every node the model has ever held, which is what the positions are worked out from rather than
     * only the nodes being shown. A node's place would otherwise move every time a neighbour appeared
     * — each one takes a share of its parent's arc, so one node arriving re-slices its whole branch.
     *
     * <p>Nodes in here that the model being shown does not hold are drawn faintly: a gap says nothing,
     * whereas a node that has not been learnt yet says what is about to happen.
     */
    PathNode getLayout() {
        return layout;
    }

    /**
     * How much each node had changed and when it last had, by node uuid, as at the moment being shown.
     */
    Map<String, NodeChange> getChanges() {
        return changes;
    }

    /**
     * How much each node had been used at the moment being shown, by node uuid. Taken from the reading
     * kept whenever a trace changed the model, because the node itself only knows how much it has been
     * used now — a node absent from this has no reading at or before the moment being shown.
     */
    Map<String, NodeUsage> getUsage() {
        return usage;
    }

    /**
     * The most any one node has ever changed, and the most any one node has ever been used. What the
     * sizes are measured against.
     *
     * <p>Taken over the whole history rather than over the part being shown. Measured against the
     * part being shown, the largest node of the moment would always be drawn at full size, so winding
     * back would rescale the picture rather than shrink it and nothing could be seen to grow.
     */
    long getChangeCeiling() {
        return changeCeiling;
    }

    long getUsageCeiling() {
        return usageCeiling;
    }

    /**
     * The moment being looked at, which is the time of the change wound back to rather than the time
     * now. How long ago a node changed is measured from here.
     */
    long getAsAt() {
        return asAt;
    }
}
