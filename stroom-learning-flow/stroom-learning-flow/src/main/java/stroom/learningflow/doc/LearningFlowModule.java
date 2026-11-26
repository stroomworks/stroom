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

package stroom.learningflow.doc;

import stroom.docstore.api.DocumentStoreBinder;
import stroom.learningflow.element.LearningFlowElementModule;
import stroom.learningflow.fragment.ContentCreator;
import stroom.learningflow.fragment.ExplorerContentCreator;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;

/// Guice module for the Learning Flow document type, its pipeline element, and its REST resources.
public class LearningFlowModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new LearningFlowElementModule());
        bind(ContentCreator.class).to(ExplorerContentCreator.class);

        DocumentStoreBinder.create(binder())
                .bind(LearningFlowDoc.TYPE, LearningFlowStore.class, LearningFlowStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(LearningFlowResourceImpl.class)
                // The Supervisor shows learning under every Learning Flow document rather than being a tab
                // on one document, so it has a resource of its own beside the document's.
                .bind(SupervisorResourceImpl.class)
                // Serves a Smart Pipeline's Quality Assurance tab, which shows one pipeline's translated
                // output and how it was judged.
                .bind(QualityResourceImpl.class);
    }
}
