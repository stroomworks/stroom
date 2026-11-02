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

package stroom.shapeshifter;

import stroom.shapeshifter.ai.doc.ShapeshifterAiModule;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.OptionalBinder;

/**
 * Shapeshifter AI in a test node: the feature's module, with the scripted advisor of design 02 §3 in
 * place of a model.
 */
public class ShapeshifterAiTestModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new ShapeshifterAiModule());
        bind(AdvisorHolder.class).in(Scopes.SINGLETON);
        OptionalBinder.newOptionalBinder(binder(), Advisors.class).setBinding().to(AdvisorHolder.class);
    }
}
