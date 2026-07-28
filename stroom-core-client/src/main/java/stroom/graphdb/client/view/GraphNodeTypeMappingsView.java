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

package stroom.graphdb.client.view;

import stroom.document.client.event.ChangeUiHandlers;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.graphdb.shared.GraphNodeTypeMapping;

import com.gwtplatform.mvp.client.HasUiHandlers;

import java.util.List;

/**
 * Task B1: the get/set contract for editing
 * {@code GraphDbDoc.nodeTypeMappings}, following the same shape as the sibling {@code RetentionSettingsView}
 * in {@code stroom.planb.client.view} so {@link stroom.graphdb.client.presenter.GraphDbSettingsPresenter}'s own
 * view can compose it directly.
 */
public interface GraphNodeTypeMappingsView extends ReadOnlyChangeHandler, HasUiHandlers<ChangeUiHandlers> {

    List<GraphNodeTypeMapping> getNodeTypeMappings();

    void setNodeTypeMappings(List<GraphNodeTypeMapping> nodeTypeMappings);
}
