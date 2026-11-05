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

package stroom.gwt.workbench.client.app.egress;

import stroom.egress.client.component.EgressPolicyController;
import stroom.gwt.workbench.client.app.gin.egress.EgressScreenGinjector;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// Stories of `App/Main/EgressPolicyScreen`, showing Stroom's real [EgressPolicyController] (the 'Egress Policy'
/// tab, as `EgressPolicyPlugin` opens it) with fake REST replies, for Stroom's `EgressResource`:
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET /egress/v1/settings` | the mode and kill switch of each kind |
/// | `GET /egress/v1/rules` | the permitted destinations |
/// | `GET /egress/v1/attempts` | what was refused or reported |
/// | `POST /egress/v1/settings/save` (echoes the settings) | ticking Enforce or Kill switch |
/// | `POST /egress/v1/rules/save` (echoes the rule, with an id) | the rule dialog's OK |
/// | `POST /egress/v1/rules/test` | the rule dialog's Test |
/// | `POST /egress/v1/attempts/reveal` | Reveal |
///
/// The screen has no read-only mode: it is for those who hold Manage Egress Policy and nobody else is shown it.
public final class EgressPolicyScreenStories {

    private static final String SETTINGS = """
            {
              "modes": {
                "HTTP": "REPORT_ONLY", "EMAIL": "REPORT_ONLY", "AI_MODEL": "REPORT_ONLY", "FILE": "ENFORCE",
                "OBJECT_STORE": "REPORT_ONLY", "MESSAGE_BUS": "REPORT_ONLY", "SEARCH_BACKEND": "REPORT_ONLY"
              },
              "killSwitches": {
                "HTTP": false, "EMAIL": false, "AI_MODEL": false, "FILE": false,
                "OBJECT_STORE": false, "MESSAGE_BUS": false, "SEARCH_BACKEND": false
              }
            }""";

    private static final String RULES = """
            [
              {"id": 1, "kind": "FILE", "destination": "/stroom/output", "match": "PREFIX",
                "comment": "Where the file appenders write", "enabled": true},
              {"id": 2, "kind": "EMAIL", "destination": "alerts@example.com", "match": "EXACT",
                "comment": "The security team's mailbox", "enabled": true}
            ]""";

    private static final String ATTEMPTS = """
            [
              {"id": "a1", "kind": "HTTP", "destination": "https://partner.example.org/hook/*****",
                "sink": "HTTPAppender", "pipeline": "Send to partner", "feed": "PARTNER_EVENTS",
                "outcome": "WOULD_DENY", "count": 3, "firstSeenMs": 1793000000000, "lastSeenMs": 1793000600000,
                "reason": "No rule permits it"}
            ]""";

    private static final String SAVED_RULE = """
            {"id": 3, "kind": "HTTP", "destination": "https://partner.example.org/hook", "match": "EXACT",
              "comment": "The partner's endpoint", "enabled": true}""";

    private static final RestFixtures FIXTURES = RestFixtures.builder()
            .get("/egress/v1/settings", RestReply.json(SETTINGS))
            .get("/egress/v1/rules", RestReply.json(RULES))
            .get("/egress/v1/attempts", RestReply.json(ATTEMPTS))
            .post("/egress/v1/settings/save", request -> RestReply.json(request.getBody()))
            .post("/egress/v1/rules/save", RestReply.json(SAVED_RULE))
            .post("/egress/v1/rules/test", RestReply.json("""
                    {"normalised": "https://partner.example.org/hook", "outcome": "ALLOWED", "ruleId": 3}"""))
            .post("/egress/v1/attempts/reveal", RestReply.text("https://partner.example.org/hook/abc123"))
            .build();

    private EgressPolicyScreenStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Main/EgressPolicyScreen", EgressPolicyScreenStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The three lists are filled from the server
                .story("Default", EgressPolicyScreenStories::render)
                .withPlay(play -> {
                    play.findByText("Search backend");
                    play.findByText("/stroom/output");
                    play.findByText("alerts@example.com");
                    play.findByText("https://partner.example.org/hook/*****");
                    play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                })
                // Turning on enforcement asks first, and saves only when it is confirmed
                .story("EnforceConfirmed", EgressPolicyScreenStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.findByText("Search backend");
                    play.click(play.within(play.getByText("Search backend").closest(StroomDom.GRID_ROW))
                            .querySelector(".tick-box"));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/egress/v1/settings/save")
                                    .withBodyContaining("\"SEARCH_BACKEND\":\"ENFORCE\"")
                                    .toSpyMatcher()));
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                })
                // Declining the confirmation saves nothing
                .story("EnforceDeclined", EgressPolicyScreenStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.findByText("Search backend");
                    play.click(play.within(play.getByText("Search backend").closest(StroomDom.GRID_ROW))
                            .querySelector(".tick-box"));
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/egress/v1/settings/save").toSpyMatcher());
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                })
                // New opens the rule dialog; OK saves what was typed
                .story("NewRuleOk", EgressPolicyScreenStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.findByText("/stroom/output");
                    play.click(play.getByTitle("New Rule"));
                    screen.findByText("New Egress Rule", StroomDom.DIALOG_TITLE);
                    play.type(screen.getByLabelText("Destination"), "https://partner.example.org/hook");
                    play.type(screen.getByLabelText("Reason"), "The partner's endpoint");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/egress/v1/rules/save")
                                    .withBodyContaining("https://partner.example.org/hook")
                                    .toSpyMatcher()));
                    play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                })
                // Cancel saves nothing
                .story("NewRuleCancel", EgressPolicyScreenStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.findByText("/stroom/output");
                    play.click(play.getByTitle("New Rule"));
                    screen.findByText("New Egress Rule", StroomDom.DIALOG_TITLE);
                    play.type(screen.getByLabelText("Destination"), "https://partner.example.org/hook");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(
                            screen.queryByText("New Egress Rule", StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/egress/v1/rules/save").toSpyMatcher());
                    play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                })
                // The server is asked for the whole destination, as the one in the list is masked
                .story("RevealAttempt", EgressPolicyScreenStories::render)
                .withPlay(play -> {
                    play.findByText("https://partner.example.org/hook/*****");
                    play.click(play.getByText("https://partner.example.org/hook/*****"));
                    play.click(play.getByTitle(
                            "Show the selected destination in full. This is recorded in the audit log."));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/egress/v1/attempts/reveal")
                                    .withBodyContaining("a1")
                                    .toSpyMatcher()));
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                });
    }

    private static Widget render(final StoryContext context) {
        final EgressScreenGinjector injector = GWT.create(EgressScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, FIXTURES)
                .injector(injector)
                .build();
        harness.afterStartUp(() -> harness.show(injector.getEgressPolicyController()));
        return harness.asWidget();
    }
}
