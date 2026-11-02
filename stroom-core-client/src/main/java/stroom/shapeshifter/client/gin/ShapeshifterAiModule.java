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

package stroom.shapeshifter.client.gin;

import stroom.core.client.gin.PluginModule;
import stroom.shapeshifter.client.ShapeshifterAiPlugin;
import stroom.shapeshifter.client.ShapeshifterAiSupervisorPlugin;

/// The Shapeshifter AI document's plugin, and the Supervisor's (A28), which puts it in the Monitoring
/// menu. Their screens need no bindings.
public class ShapeshifterAiModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(ShapeshifterAiPlugin.class);
        bindPlugin(ShapeshifterAiSupervisorPlugin.class);
    }
}
