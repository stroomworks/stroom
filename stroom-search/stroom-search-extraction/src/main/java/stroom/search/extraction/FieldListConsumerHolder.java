package stroom.search.extraction;

import stroom.query.common.v2.StringFieldValue;
import stroom.util.pipeline.scope.PipelineScoped;

import jakarta.inject.Inject;

import java.util.List;

@PipelineScoped
public class FieldListConsumerHolder implements FieldListConsumer {

    private final ExtractionState extractionState;
    private FieldListConsumer fieldListConsumer;

    @Inject
    FieldListConsumerHolder(final ExtractionState extractionState) {
        this.extractionState = extractionState;
    }

    @Override
    public void acceptFieldValues(final List<FieldValue> fieldValues) {
        fieldListConsumer.acceptFieldValues(fieldValues);
        extractionState.incrementCount();
    }

    @Override
    public void acceptStringValues(final List<StringFieldValue> stringValues) {
        fieldListConsumer.acceptStringValues(stringValues);
        extractionState.incrementCount();
    }

    public void setFieldListConsumer(final FieldListConsumer fieldListConsumer) {
        this.fieldListConsumer = fieldListConsumer;
    }
}
