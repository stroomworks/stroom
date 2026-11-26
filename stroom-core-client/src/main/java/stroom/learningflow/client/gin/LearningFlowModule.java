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

package stroom.learningflow.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.learningflow.client.LearningFlowPlugin;
import stroom.learningflow.client.LearningFlowSupervisorPlugin;

/// Registers the client plugins for Learning Flow: the one for the Learning Flow document type, and the one
/// that adds the Supervisor (the screen for watching and steering learning sessions) to the Monitoring menu.
/// Their screens are made by GIN without any explicit bindings.
public class LearningFlowModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(LearningFlowPlugin.class);
        bindPlugin(LearningFlowSupervisorPlugin.class);
    }
}
