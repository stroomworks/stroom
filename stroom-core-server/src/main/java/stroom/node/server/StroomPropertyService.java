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

package stroom.node.server;

/**
 * A service that can be injected with spring that caches and delegates property
 * lookups to StroomProperties.
 */
public interface StroomPropertyService {
    String getProperty(String name);

    int getIntProperty(String propertyName, int defaultValue);

    long getLongProperty(String propertyName, long defaultValue);

    boolean getBooleanProperty(String propertyName, boolean defaultValue);
}
