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

package stroom.shapeshifter.client.component;

import stroom.widget.button.client.Button;
import stroom.widget.component.client.Component;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/// The process a stream meets, drawn small in the document's toolbar row (design 05 §10; A98): the
/// stages in the order they happen, the open one marked, each a button to its tab, and beside each the
/// document's own figure from the Supervisor — the streams waiting on a session, the sessions parked for
/// a person. The document says what should happen; these say what is happening.
public class ProcessHeader extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final Templates TEMPLATES = GWT.create(Templates.class);

    @UiField
    Button score;
    @UiField
    Button learn;

    private final Map<Stage, Button> buttons = new EnumMap<>(Stage.class);
    private final Map<Stage, String> figures = new EnumMap<>(Stage.class);
    private Consumer<Stage> onStage = stage -> {
    };

    /// Makes the header with no stage open and no figures.
    public ProcessHeader() {
        initWidget(BINDER.createAndBindUi(this));
        buttons.put(Stage.SCORE, score);
        buttons.put(Stage.LEARN, learn);
        buttons.forEach((stage, button) -> {
            figures.put(stage, "");
            button.setOnClick(() -> onStage.accept(stage));
            draw(stage);
        });
    }

    /// @param onStage Told which stage was pressed, to open its tab.
    public void setOnStage(final Consumer<Stage> onStage) {
        this.onStage = onStage;
    }

    /// Marks the open stage (`aria-current`), or none where another tab is open.
    ///
    /// @param open The stage whose tab is open, or `null` for none.
    public void setStage(final Stage open) {
        buttons.forEach((stage, button) -> {
            if (stage == open) {
                button.getElement().setAttribute("aria-current", "step");
            } else {
                button.getElement().removeAttribute("aria-current");
            }
        });
    }

    /// The document's figures from the Supervisor.
    ///
    /// @param waiting The streams waiting on a session of the document's, or `null` for none known.
    /// @param parked  The sessions of the document's parked for a person, or `null` for none known.
    public void setFigures(final Long waiting, final Long parked) {
        figures.put(Stage.SCORE, waiting == null
                ? ""
                : waiting + (waiting == 1
                        ? " stream waiting"
                        : " streams waiting"));
        figures.put(Stage.LEARN, parked == null
                ? ""
                : parked + (parked == 1
                        ? " session parked"
                        : " sessions parked"));
        draw(Stage.SCORE);
        draw(Stage.LEARN);
    }

    // A stage's button: its title, what it is, and its figure, which is the button's name too
    private void draw(final Stage stage) {
        buttons.get(stage).setContent(TEMPLATES.stage(stage.title, stage.caption, figures.get(stage)));
    }


    // --------------------------------------------------------------------------------


    /// The two stages, in the order a stream meets them (A110): which streams reach the document is its
    /// pipelines' filters' to say.
    public enum Stage {
        SCORE("Score", "the bar, and the gate to a binding"),
        LEARN("Learn", "the flow a session walks");

        private final String title;
        private final String caption;

        Stage(final String title, final String caption) {
            this.title = title;
            this.caption = caption;
        }
    }

    interface Binder extends UiBinder<Widget, ProcessHeader> {

    }


    // --------------------------------------------------------------------------------


    interface Templates extends SafeHtmlTemplates {

        // A stage's button's content: its title, what it is, and its figure (escaped by the template)
        @Template("<span class=\"process-header__title\">{0}</span>"
                  + "<span class=\"process-header__caption\">{1}</span>"
                  + "<span class=\"process-header__figure\">{2}</span>")
        SafeHtml stage(String title, String caption, String figure);
    }
}
