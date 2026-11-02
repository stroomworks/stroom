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

package stroom.pipelinefactory.impl;

import stroom.config.common.AbstractDbConfig;
import stroom.config.common.ConnectionConfig;
import stroom.config.common.ConnectionPoolConfig;
import stroom.util.shared.BootStrapConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/// The database holding what each Pipeline Factory has met and how far it has looked (design 06 §3.4).
@BootStrapConfig
public class PipelineFactoryDbConfig extends AbstractDbConfig {

    /// The default connection, which is the common database's.
    public PipelineFactoryDbConfig() {
        super();
    }

    /// @param connectionConfig     The connection.
    /// @param connectionPoolConfig The connection pool.
    @SuppressWarnings("unused")
    @JsonCreator
    public PipelineFactoryDbConfig(
            @JsonProperty(PROP_NAME_CONNECTION) final ConnectionConfig connectionConfig,
            @JsonProperty(PROP_NAME_CONNECTION_POOL) final ConnectionPoolConfig connectionPoolConfig) {
        super(connectionConfig, connectionPoolConfig);
    }
}
