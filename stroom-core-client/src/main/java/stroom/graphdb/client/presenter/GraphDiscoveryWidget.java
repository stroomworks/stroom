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

package stroom.graphdb.client.presenter;

import stroom.graphdb.shared.GraphDbSchema;

import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

import java.util.Map;
import java.util.function.Consumer;

/**
 * The Graph DB Data tab's discovery panel: shows what a graph currently holds - node labels, relationship types,
 * property keys and a few example nodes - as a set of clickable starter queries, so an analyst who does not yet
 * know the graph's shape can get going. Clicking a suggestion hands its query text back through the supplied
 * callback (the presenter drops it into the query box and runs it).
 *
 * <p>Only queries that always execute are made clickable: the whole-graph preview and the per-label preview (both
 * ride the engine's store-walk path). Relationship types and property keys are shown for reference (to use in a
 * pattern / {@code WHERE}); an example node offers a best-effort anchored template on its first label + property.</p>
 */
public class GraphDiscoveryWidget extends Composite {

    private static final int WHOLE_GRAPH_LIMIT = 100;
    private static final int LABEL_LIMIT = 100;
    private static final int MAX_INLINE_PROPS = 3;

    private final FlowPanel panel;
    private final Consumer<String> onApplyQuery;
    private final Consumer<String> onFocusNode;

    /**
     * @param onApplyQuery runs a reliable, always-valid query (whole-graph / label preview).
     * @param onFocusNode  focuses on a specific node by its id - identity-based via the /expand endpoint, so it
     *                     resolves regardless of which property is indexed (unlike a property-anchored query).
     */
    public GraphDiscoveryWidget(final Consumer<String> onApplyQuery, final Consumer<String> onFocusNode) {
        this.onApplyQuery = onApplyQuery;
        this.onFocusNode = onFocusNode;
        panel = new FlowPanel();
        panel.addStyleName("GraphDiscovery");
        initWidget(panel);
    }

    public void setSchema(final GraphDbSchema schema) {
        panel.clear();

        if (schema == null || schema.isEmpty()) {
            panel.add(message("This graph is empty - once data is ingested, its labels, relationship types and "
                    + "example nodes will appear here to help you build a query."));
            return;
        }

        final FlowPanel start = section("Start here");
        start.add(clickable("Show the whole graph", "MATCH (n) RETURN GRAPH LIMIT " + WHOLE_GRAPH_LIMIT));
        panel.add(start);

        if (!schema.getNodeLabels().isEmpty()) {
            final FlowPanel labels = section("Node labels - click to preview");
            for (final String label : schema.getNodeLabels()) {
                labels.add(clickable(label, "MATCH (n:" + label + ") RETURN GRAPH LIMIT " + LABEL_LIMIT));
            }
            panel.add(labels);
        }

        if (!schema.getEdgeTypes().isEmpty()) {
            final FlowPanel edges = section("Relationship types - use in a pattern, e.g. -[:TYPE]->");
            for (final String type : schema.getEdgeTypes()) {
                edges.add(reference(type));
            }
            panel.add(edges);
        }

        if (!schema.getPropertyKeys().isEmpty()) {
            final FlowPanel keys = section("Property keys - use in RETURN or WHERE");
            for (final String key : schema.getPropertyKeys()) {
                keys.add(reference(key));
            }
            panel.add(keys);
        }

        if (!schema.getSampleNodes().isEmpty()) {
            final FlowPanel samples = section("Example nodes - click to focus on one");
            for (final GraphDbSchema.SampleNode node : schema.getSampleNodes()) {
                samples.add(sampleNode(node));
            }
            panel.add(samples);
        }
    }

    private Widget sampleNode(final GraphDbSchema.SampleNode node) {
        final StringBuilder text = new StringBuilder();
        if (!node.getLabels().isEmpty()) {
            text.append(node.getLabels().get(0)).append("  ");
        }
        text.append(node.getId());
        int shown = 0;
        for (final Map.Entry<String, String> property : node.getProperties().entrySet()) {
            text.append("   ").append(property.getKey()).append('=').append(property.getValue());
            if (++shown >= MAX_INLINE_PROPS) {
                break;
            }
        }
        // Focus by id (identity-based, always resolves) - not a property-anchored query, which would return
        // nothing unless that (label, property) pair happens to be indexed.
        return node.getId() != null
                ? focusChip(text.toString(), node.getId())
                : reference(text.toString());
    }

    private Label clickable(final String text, final String query) {
        final Label chip = new Label(text);
        chip.addStyleName("GraphDiscovery-chip GraphDiscovery-chip__clickable");
        chip.setTitle(query);
        chip.addClickHandler(event -> {
            if (onApplyQuery != null) {
                onApplyQuery.accept(query);
            }
        });
        return chip;
    }

    private Label focusChip(final String text, final String nodeId) {
        final Label chip = new Label(text);
        chip.addStyleName("GraphDiscovery-chip GraphDiscovery-chip__clickable");
        chip.setTitle("Focus on this node");
        chip.addClickHandler(event -> {
            if (onFocusNode != null) {
                onFocusNode.accept(nodeId);
            }
        });
        return chip;
    }

    private static Label reference(final String text) {
        final Label chip = new Label(text);
        chip.addStyleName("GraphDiscovery-chip");
        return chip;
    }

    private static FlowPanel section(final String title) {
        final FlowPanel section = new FlowPanel();
        section.addStyleName("GraphDiscovery-section");
        final Label heading = new Label(title);
        heading.addStyleName("GraphDiscovery-heading");
        section.add(heading);
        return section;
    }

    private static Label message(final String text) {
        final Label label = new Label(text);
        label.addStyleName("GraphDiscovery-message");
        return label;
    }
}
