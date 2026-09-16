/*
 * Copyright 2025 Crown Copyright
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

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.feed.shared.FeedDoc;
import stroom.pathways.client.presenter.PathwaysSettingsPresenter.PathwaysSettingsView;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.NullSafe;
import stroom.util.shared.time.SimpleDuration;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

public class PathwaysSettingsPresenter extends DocPresenter<PathwaysSettingsView, PathwaysDoc>
        implements PathwaysSettingsUiHandlers {

    private final DocSelectionBoxPresenter feedPresenter;

    @Inject
    public PathwaysSettingsPresenter(final EventBus eventBus,
                                     final PathwaysSettingsView view,
                                     final DocSelectionBoxPresenter feedPresenter) {
        super(eventBus, view);
        this.feedPresenter = feedPresenter;
        view.setUiHandlers(this);

        feedPresenter.setIncludedTypes(FeedDoc.TYPE);
        feedPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        view.setInfoFeedView(feedPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(feedPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final PathwaysDoc doc, final boolean readOnly) {
        getView().setTemporalOrderingTolerance(doc.getTemporalOrderingTolerance());
        getView().setAllowPathwayCreation(doc.isAllowPathwayCreation());
        getView().setAllowPathwayMutation(doc.isAllowPathwayMutation());
        getView().setAllowConstraintCreation(doc.isAllowConstraintCreation());
        getView().setAllowConstraintMutation(doc.isAllowConstraintMutation());
        feedPresenter.setSelectedEntityReference(doc.getInfoFeed(), true);
        getView().setSharedFileStore(doc.getSharedFileStore());
    }

    @Override
    protected PathwaysDoc onWrite(final PathwaysDoc doc) {
        return doc
                .copy()
                .temporalOrderingTolerance(getView().getTemporalOrderingTolerance())
                .allowPathwayCreation(getView().isAllowPathwayCreation())
                .allowPathwayMutation(getView().isAllowPathwayMutation())
                .allowConstraintCreation(getView().isAllowConstraintCreation())
                .allowConstraintMutation(getView().isAllowConstraintMutation())
                .infoFeed(feedPresenter.getSelectedEntityReference())
                .sharedFileStore(configuredSharedFileStore())
                .build();
    }

    // The editor always hands back a settings object, filling an untouched form in as an empty path and
    // a shard count of one. Storing that would make every document look configured, so a blank path is
    // written as no shared file store at all, which is what the document's absent state means.
    private SharedFileStoreSettings configuredSharedFileStore() {
        final SharedFileStoreSettings settings = getView().getSharedFileStore();
        return settings == null || NullSafe.isBlankString(settings.getSharedPath())
                ? null
                : settings;
    }

    public interface PathwaysSettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<PathwaysSettingsUiHandlers> {

        void setInfoFeedView(View view);

        SimpleDuration getTemporalOrderingTolerance();

        void setTemporalOrderingTolerance(SimpleDuration temporalOrderingTolerance);

        boolean isAllowPathwayCreation();

        void setAllowPathwayCreation(boolean allowPathwayCreation);

        boolean isAllowPathwayMutation();

        void setAllowPathwayMutation(boolean allowPathwayMutation);

        boolean isAllowConstraintCreation();

        void setAllowConstraintCreation(boolean allowConstraintCreation);

        boolean isAllowConstraintMutation();

        void setAllowConstraintMutation(boolean allowConstraintMutation);

        SharedFileStoreSettings getSharedFileStore();

        void setSharedFileStore(SharedFileStoreSettings sharedFileStore);
    }
}
