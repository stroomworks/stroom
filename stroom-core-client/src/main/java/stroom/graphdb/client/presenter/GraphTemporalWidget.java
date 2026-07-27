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

import stroom.graphdb.shared.GraphTemporal;

import com.google.gwt.core.client.Duration;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.InputElement;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.ToggleButton;
import com.google.gwt.user.client.ui.Widget;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Graph DB Data tab's temporal "time-travel" slider (Cytoscape UI extension). It divides a user-set
 * [from, to] window into {@link #STEPS} instants; moving the slider, stepping, or playing re-runs the displayed
 * query at that instant by asking the presenter to render an {@code AS OF} snapshot (see
 * {@link GraphTemporal#withAsOf}). Turning it off restores the live view.
 *
 * <p>Each tick re-runs the query through the normal search path, so the graph re-renders (and re-lays-out) at the
 * chosen instant - a deliberately simple v1; frame-to-frame diff/animation of added/removed elements (reusing the
 * existing {@code changeKind} styling) is a natural follow-up.</p>
 */
public class GraphTemporalWidget extends Composite {

    /** Number of slider stops across the [from, to] window. */
    private static final int STEPS = 20;
    private static final long DEFAULT_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final int PLAY_INTERVAL_MILLIS = 1500;

    private final Consumer<Long> onSnapshot;
    private final Runnable onLive;
    private final BiConsumer<Long, Long> onCompare;

    private final FlowPanel controls;
    private final TextBox fromBox;
    private final TextBox toBox;
    private final RangeSlider slider;
    private final Label instantLabel;
    private final ToggleButton playButton;

    private final Timer playTimer = new Timer() {
        @Override
        public void run() {
            if (slider.getValue() >= STEPS) {
                setPlaying(false);
                return;
            }
            slider.setValue(slider.getValue() + 1);
            emit();
        }
    };

    /**
     * @param onSnapshot rendered at the given epoch-millis instant (an {@code AS OF} run of the displayed query).
     * @param onLive     restore the live (latest) view when time-travel is switched off.
     * @param onCompare  render a {@code DIFF} between the window's start and end instants (added / removed /
     *                   modified / unchanged), styled by the graph's {@code changeKind} rules.
     */
    public GraphTemporalWidget(final Consumer<Long> onSnapshot,
                               final Runnable onLive,
                               final BiConsumer<Long, Long> onCompare) {
        this.onSnapshot = onSnapshot;
        this.onLive = onLive;
        this.onCompare = onCompare;

        final FlowPanel panel = new FlowPanel();
        panel.addStyleName("GraphTemporal");

        // Activation is driven externally by the in-graph toolbar's "Time travel" toggle (relayed through the
        // sandbox's reverse channel to GraphResultWidget, which calls #setActive) rather than by a button here,
        // so the panel shows no chrome until it is switched on.
        controls = new FlowPanel();
        controls.addStyleName("GraphTemporal-controls");
        controls.setVisible(false);

        final long now = (long) Duration.currentTimeMillis();
        fromBox = timeBox(GraphTemporal.formatIsoUtc(now - DEFAULT_WINDOW_MILLIS), "Window start (ISO-8601 UTC)");
        toBox = timeBox(GraphTemporal.formatIsoUtc(now), "Window end (ISO-8601 UTC)");

        final Button prev = stepButton("◀", "Step back", () -> step(-1));
        playButton = new ToggleButton("Play");
        playButton.addStyleName("GraphTemporal-play");
        playButton.setTitle("Play through the window");
        playButton.addClickHandler(event -> setPlaying(playButton.isDown()));
        final Button next = stepButton("▶", "Step forward", () -> step(1));

        slider = new RangeSlider(STEPS);
        slider.addStyleName("GraphTemporal-slider");
        slider.setOnChange(this::emit);

        instantLabel = new Label();
        instantLabel.addStyleName("GraphTemporal-instant");

        controls.add(caption("From"));
        controls.add(fromBox);
        controls.add(caption("To"));
        controls.add(toBox);
        controls.add(prev);
        controls.add(playButton);
        controls.add(next);
        controls.add(slider);
        final Button compare = stepButton("Compare", "Diff the window start against its end", this::compareWindow);
        compare.removeStyleName("GraphTemporal-step");
        compare.addStyleName("GraphTemporal-compare");
        controls.add(compare);
        controls.add(instantLabel);

        panel.add(controls);
        initWidget(panel);
    }

    /**
     * Show or hide the time-travel controls. Called by {@link GraphResultWidget} when the in-graph toolbar's
     * "Time travel" toggle is switched. Switching off stops any playback and restores the live (latest) view.
     */
    public void setActive(final boolean on) {
        controls.setVisible(on);
        if (on) {
            slider.setValue(0);
            emit();
        } else {
            setPlaying(false);
            if (onLive != null) {
                onLive.run();
            }
        }
    }

    private void setPlaying(final boolean play) {
        if (playButton.isDown() != play) {
            playButton.setDown(play);
        }
        playButton.setText(play ? "Pause" : "Play");
        if (play) {
            playTimer.scheduleRepeating(PLAY_INTERVAL_MILLIS);
        } else {
            playTimer.cancel();
        }
    }

    private void step(final int delta) {
        int value = slider.getValue() + delta;
        value = Math.max(0, Math.min(STEPS, value));
        slider.setValue(value);
        emit();
    }

    /** Compute the current instant from the slider position within [from, to] and ask for its snapshot. */
    private void emit() {
        final Long instant = currentInstant();
        if (instant == null) {
            instantLabel.setText("Enter valid ISO-8601 times");
            instantLabel.addStyleName("GraphTemporal-instant__error");
            return;
        }
        instantLabel.removeStyleName("GraphTemporal-instant__error");
        instantLabel.setText(GraphTemporal.formatIsoUtc(instant));
        if (onSnapshot != null) {
            onSnapshot.accept(instant);
        }
    }

    /** Render a DIFF of the whole [from, to] window. Stops any playback first (a diff is a single render). */
    private void compareWindow() {
        setPlaying(false);
        final long from;
        final long to;
        try {
            from = GraphTemporal.parseIsoUtc(fromBox.getText());
            to = GraphTemporal.parseIsoUtc(toBox.getText());
        } catch (final IllegalArgumentException e) {
            instantLabel.setText("Enter valid ISO-8601 times");
            instantLabel.addStyleName("GraphTemporal-instant__error");
            return;
        }
        instantLabel.removeStyleName("GraphTemporal-instant__error");
        instantLabel.setText("DIFF " + GraphTemporal.formatIsoUtc(from) + " → " + GraphTemporal.formatIsoUtc(to));
        if (onCompare != null) {
            onCompare.accept(from, to);
        }
    }

    private Long currentInstant() {
        final long from;
        final long to;
        try {
            from = GraphTemporal.parseIsoUtc(fromBox.getText());
            to = GraphTemporal.parseIsoUtc(toBox.getText());
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (to < from) {
            return null;
        }
        final double fraction = (double) slider.getValue() / (double) STEPS;
        return from + Math.round((to - from) * fraction);
    }

    private TextBox timeBox(final String initial, final String title) {
        final TextBox box = new TextBox();
        box.setText(initial);
        box.setTitle(title);
        box.addStyleName("GraphTemporal-time");
        box.addValueChangeHandler(event -> emit());
        return box;
    }

    private static Button stepButton(final String glyph, final String title, final Runnable action) {
        final Button button = new Button(glyph);
        button.setTitle(title);
        button.addStyleName("GraphTemporal-step");
        button.addClickHandler(event -> action.run());
        return button;
    }

    /** A small inline caption. */
    private static Label caption(final String text) {
        final Label label = new Label(text);
        label.addStyleName("GraphTemporal-caption");
        return label;
    }

    /**
     * A minimal wrapper over a native {@code <input type="range">}. Fires on {@code change} (slider release), not
     * on every drag pixel - exactly what we want, since each change re-runs a query.
     */
    private static final class RangeSlider extends Widget {

        private final InputElement input;
        private Runnable onChange;

        private RangeSlider(final int steps) {
            input = Document.get().createElement("input").cast();
            input.setAttribute("type", "range");
            input.setAttribute("min", "0");
            input.setAttribute("max", String.valueOf(steps));
            input.setAttribute("step", "1");
            input.setValue("0");
            setElement(input);
            sinkEvents(Event.ONCHANGE);
        }

        private void setOnChange(final Runnable onChange) {
            this.onChange = onChange;
        }

        private int getValue() {
            try {
                return Integer.parseInt(input.getValue());
            } catch (final NumberFormatException e) {
                return 0;
            }
        }

        private void setValue(final int value) {
            input.setValue(String.valueOf(value));
        }

        @Override
        public void onBrowserEvent(final Event event) {
            super.onBrowserEvent(event);
            if (Event.ONCHANGE == event.getTypeInt() && onChange != null) {
                onChange.run();
            }
        }
    }
}
