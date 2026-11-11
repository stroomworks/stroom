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

package stroom.dependency.client.layout;

import stroom.dependency.shared.GraphNode;

/// One thing the layout places: a node, one copy of a referenced document, or a summary standing in for
/// several of them.
///
/// A graph node can have several instances, because a referenced document is drawn once in every lane
/// that uses it. `id` identifies the instance, and `src` the graph node it draws.
///
/// The column and position are boxed, because the layout distinguishes "not set" from zero.
public final class Instance {

    /// The document type an endpoint outside Stroom is drawn as.
    public static final String ENDPOINT_TYPE = "Endpoint";

    /// The document type a reference summary is drawn as.
    public static final String REFERENCE_SUMMARY_TYPE = "XSLT";

    private final String id;
    private final String src;
    private final InstanceKind kind;
    private final String type;
    private final String name;
    private final GraphNode node;
    private final int lane;
    private final Integer col;
    private final Integer shared;
    private final Integer count;
    private final String host;
    private final Boolean pinned;

    private Integer x;
    private Integer y;

    private Instance(final String id,
                     final String src,
                     final InstanceKind kind,
                     final String type,
                     final String name,
                     final GraphNode node,
                     final int lane,
                     final Integer col,
                     final Integer shared,
                     final Integer count,
                     final String host,
                     final Boolean pinned) {
        this.id = id;
        this.src = src;
        this.kind = kind;
        this.type = type;
        this.name = name;
        this.node = node;
        this.lane = lane;
        this.col = col;
        this.shared = shared;
        this.count = count;
        this.host = host;
        this.pinned = pinned;
    }

    static Instance flow(final GraphNode node, final int lane, final int col) {
        return new Instance(node.getId(),
                node.getId(),
                InstanceKind.FLOW,
                typeOf(node),
                nameOf(node),
                node,
                lane,
                col,
                null,
                null,
                null,
                null);
    }

    static Instance reference(final GraphNode node, final int lane, final int shared) {
        return new Instance("ref:" + lane + ":" + node.getId(),
                node.getId(),
                InstanceKind.REFERENCE,
                typeOf(node),
                nameOf(node),
                node,
                lane,
                null,
                shared,
                null,
                null,
                null);
    }

    static Instance endpoint(final GraphNode node, final int lane, final String host) {
        // Two hosts in one lane give two endpoints the same id. A quirk of the reference layout, kept so
        // the port gives the same result.
        return new Instance("ep:" + lane + ":" + node.getId(),
                node.getId(),
                InstanceKind.ENDPOINT,
                ENDPOINT_TYPE,
                nameOf(node),
                node,
                lane,
                null,
                null,
                null,
                host,
                null);
    }

    static Instance laneSummary(final int lane, final Instance head, final int count, final boolean pinned) {
        return new Instance(GraphLayout.lanePinKey(lane),
                null,
                InstanceKind.LANE_SUMMARY,
                head.type,
                head.name,
                null,
                lane,
                0,
                null,
                count,
                null,
                pinned);
    }

    static Instance referenceSummary(final int lane, final int count, final boolean pinned) {
        // The summary's id is `refsum:L` but its pin key is `refs:L`, as in the reference layout.
        return new Instance("refsum:" + lane,
                null,
                InstanceKind.REFERENCE_SUMMARY,
                REFERENCE_SUMMARY_TYPE,
                count + " referenced",
                null,
                lane,
                null,
                null,
                count,
                null,
                pinned);
    }

    private static String typeOf(final GraphNode node) {
        return node.getDocRef() == null
                ? null
                : node.getDocRef().getType();
    }

    private static String nameOf(final GraphNode node) {
        return node.getDocRef() == null
                ? null
                : node.getDocRef().getName();
    }

    /// @return the instance's id, unique among the instances of one layout except where noted on
    /// endpoints.
    public String getId() {
        return id;
    }

    /// @return the id of the graph node drawn, or null for a summary.
    public String getSrc() {
        return src;
    }

    /// @return what the instance draws.
    public InstanceKind getKind() {
        return kind;
    }

    /// @return the document type to draw it as.
    public String getType() {
        return type;
    }

    /// @return the name to label it with.
    public String getName() {
        return name;
    }

    /// @return the graph node drawn, or null for a summary.
    public GraphNode getNode() {
        return node;
    }

    /// @return the lane the instance is in.
    public int getLane() {
        return lane;
    }

    /// @return the flow column, for a flow node or lane summary; null otherwise.
    public Integer getCol() {
        return col;
    }

    /// @return for a referenced document, how many lanes it is drawn in; null otherwise.
    public Integer getShared() {
        return shared;
    }

    /// @return for a summary, how many instances it stands for; null otherwise.
    public Integer getCount() {
        return count;
    }

    /// @return for an endpoint, the id of the instance that calls it; null otherwise.
    public String getHost() {
        return host;
    }

    /// @return for a summary, whether a pin rather than the zoom decided it; null otherwise.
    public Boolean getPinned() {
        return pinned;
    }

    /// @return the left edge, or null if not placed.
    public Integer getX() {
        return x;
    }

    /// @return the top edge, or null if not placed.
    public Integer getY() {
        return y;
    }

    /// @return true if this is a flow node whose pipeline has elements to show when expanded.
    public boolean isExpandable() {
        return kind == InstanceKind.FLOW
               && node != null
               && node.getElementCount() != null
               && node.getElementCount() != 0;
    }

    void setX(final Integer x) {
        this.x = x;
    }

    void setY(final Integer y) {
        this.y = y;
    }

    @Override
    public String toString() {
        return "Instance{"
               + "id='" + id + '\''
               + ", kind=" + kind
               + ", lane=" + lane
               + ", col=" + col
               + ", x=" + x
               + ", y=" + y
               + '}';
    }
}
