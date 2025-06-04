/*
 * Copyright 2016 Crown Copyright
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

package stroom.widget.menu.client.presenter;

import stroom.widget.util.client.KeyBinding.Action;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.user.client.Command;

public class SimpleMenuItem extends MenuItem {

    protected SimpleMenuItem(final int priority,
                             final SafeHtml text,
                             final SafeHtml tooltip,
                             final Action action,
                             final boolean enabled,
                             final Command command) {
        super(priority, text, tooltip, action, enabled, command);
    }

    public static class Builder extends AbstractBuilder<SimpleMenuItem, Builder> {

        @Override
        protected Builder self() {
            return this;
        }

        public SimpleMenuItem build() {
            return new SimpleMenuItem(
                    priority,
                    text,
                    tooltip,
                    action,
                    enabled,
                    command);
        }
    }
}
