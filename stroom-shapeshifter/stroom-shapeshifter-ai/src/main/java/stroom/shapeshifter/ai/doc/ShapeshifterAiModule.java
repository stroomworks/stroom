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

package stroom.shapeshifter.ai.doc;

import stroom.docstore.api.DocumentStoreBinder;
import stroom.processor.api.SupervisedPipelines;
import stroom.shapeshifter.ai.element.ShapeshifterAiElementModule;
import stroom.shapeshifter.ai.element.SupervisedPipelineCheck;
import stroom.shapeshifter.ai.fragment.ContentCreator;
import stroom.shapeshifter.ai.fragment.ExplorerContentCreator;
import stroom.shapeshifter.ai.fragment.FragmentCheck;
import stroom.shapeshifter.ai.fragment.FragmentCheckImpl;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;

public class ShapeshifterAiModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new ShapeshifterAiElementModule());
        bind(FragmentCheck.class).to(FragmentCheckImpl.class);
        // Where a filter is made, the processor asks whether the pipeline is supervised (design 04 §7).
        GuiceUtil.buildMultiBinder(binder(), SupervisedPipelines.class)
                .addBinding(SupervisedPipelineCheck.class);
        bind(ContentCreator.class).to(ExplorerContentCreator.class);

        DocumentStoreBinder.create(binder())
                .bind(ShapeshifterAiDoc.TYPE, ShapeshifterAiStore.class, ShapeshifterAiStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(ShapeshifterAiResourceImpl.class)
                // The Supervisor is a view of every document's attempts (A28), not a tab on one, so its
                // resource sits beside the document's rather than inside it.
                .bind(SupervisorResourceImpl.class);
    }
}
