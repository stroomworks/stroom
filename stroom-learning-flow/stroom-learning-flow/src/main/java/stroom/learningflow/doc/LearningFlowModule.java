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
import stroom.learningflow.fragment.FragmentCheck;
import stroom.learningflow.fragment.FragmentCheckImpl;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;

public class LearningFlowModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new LearningFlowElementModule());
        bind(FragmentCheck.class).to(FragmentCheckImpl.class);
        bind(ContentCreator.class).to(ExplorerContentCreator.class);

        DocumentStoreBinder.create(binder())
                .bind(LearningFlowDoc.TYPE, LearningFlowStore.class, LearningFlowStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(LearningFlowResourceImpl.class)
                // The Supervisor is a view of every document's attempts (A28), not a tab on one, so its
                // resource sits beside the document's rather than inside it.
                .bind(SupervisorResourceImpl.class)
                // A Smart Pipeline's Quality Assurance tab: one pipeline's translated output, judged
                .bind(QualityResourceImpl.class);
    }
}
