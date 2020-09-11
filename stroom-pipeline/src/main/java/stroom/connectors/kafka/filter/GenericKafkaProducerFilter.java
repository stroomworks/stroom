/*
 * Copyright 2017 Crown Copyright
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

package stroom.connectors.kafka.filter;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import stroom.connectors.kafka.StroomKafkaProducerFactoryService;
import stroom.pipeline.server.LocationFactoryProxy;
import stroom.pipeline.server.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.server.factory.ConfigurableElement;
import stroom.pipeline.server.factory.PipelineProperty;
import stroom.pipeline.server.writer.PathCreator;
import stroom.pipeline.shared.ElementIcons;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.util.spring.StroomScope;
import stroom.util.task.TaskMonitor;

import javax.inject.Inject;

/**
 * A generic kakfa producer filter for sending in XML content to a kafka topic.  Currently the whole XML document
 * is sent as a single kafka message with a key specified in the filter properties in the UI.
 * <p>
 * TODO It would be quite good to be able to set the key as a substitution variable e.g. ${userid} such that it
 * finds an element in the document with that name and uses its value as the key.
 * <p>
 * TODO we may also want a way of breaking up the data in individual atomic events rather than a single kafka message
 * containing a batch of events
 */
@SuppressWarnings("unused")
@Component
@Scope(StroomScope.PROTOTYPE)
@ConfigurableElement(
        type = "GenericKafkaProducerFilter",
        category = PipelineElementType.Category.FILTER,
        roles = {PipelineElementType.ROLE_TARGET,
                PipelineElementType.ROLE_HAS_TARGETS,
                PipelineElementType.VISABILITY_SIMPLE},
        icon = ElementIcons.KAFKA)
public class GenericKafkaProducerFilter extends AbstractKafkaProducerFilter {

    private final PathCreator pathCreator;

    private String topic;
    private String recordKey;

    @Inject
    public GenericKafkaProducerFilter(final ErrorReceiverProxy errorReceiverProxy,
                                      final LocationFactoryProxy locationFactory,
                                      final StroomKafkaProducerFactoryService stroomKafkaProducerFactoryService,
                                      final PathCreator pathCreator) {
        super(errorReceiverProxy, locationFactory, stroomKafkaProducerFactoryService);
        this.pathCreator = pathCreator;
    }

    @PipelineProperty(description = "This key to apply to the records, used to select partition.")
    public void setRecordKey(final String recordKey) {
        this.recordKey = pathCreator.replaceAll(recordKey);
    }

    @PipelineProperty(description = "The topic to send the record to.")
    public void setTopic(final String topic) {
        this.topic = pathCreator.replaceAll(topic);
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public String getRecordKey() {
        return recordKey;
    }
}