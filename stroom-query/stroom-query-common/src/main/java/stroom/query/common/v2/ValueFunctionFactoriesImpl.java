package stroom.query.common.v2;

import stroom.query.api.datasource.FieldType;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactories;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactory;
import stroom.util.date.DateUtil;
import stroom.util.shared.filter.FilterFieldDefinition;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class ValueFunctionFactoriesImpl<T> implements ValueFunctionFactories<T> {

    private final Map<String, ValueFunctionFactory<T>> map = new HashMap<>();

    @Override
    public ValueFunctionFactory<T> get(final String fieldName) {
        return map.get(fieldName);
    }

    public ValueFunctionFactoriesImpl<T> put(final String name,
                                             final ValueFunctionFactory<T> valueFunctionFactory) {
        map.put(name, valueFunctionFactory);
        return this;
    }

    public ValueFunctionFactoriesImpl<T> put(final FilterFieldDefinition fieldDefinition,
                                             final ValueFunctionFactory<T> valueFunctionFactory) {
        map.put(fieldDefinition.getFilterQualifier(), valueFunctionFactory);
        return this;
    }

    public ValueFunctionFactoriesImpl<T> put(final FilterFieldDefinition fieldDefinition,
                                             final Function<T, String> function) {
        map.put(fieldDefinition.getFilterQualifier(), new TextValueFunctionFactory<>(function));
        return this;
    }

    private static class TextValueFunctionFactory<T> implements ValueFunctionFactory<T> {

        private final Function<T, String> fieldExtractor;

        public TextValueFunctionFactory(final Function<T, String> fieldExtractor) {
            this.fieldExtractor = fieldExtractor;
        }

        @Override
        public Function<T, Boolean> createNullCheck() {
            return t -> Objects.isNull(fieldExtractor.apply(t));
        }

        @Override
        public Function<T, String> createStringExtractor() {
            return t -> fieldExtractor.apply(t);
        }

        @Override
        public Function<T, Long> createDateExtractor() {
            return t -> {
                final String string = fieldExtractor.apply(t);
                if (string == null) {
                    return null;
                }
                return DateUtil.parseNormalDateTimeString(string);
            };
        }

        @Override
        public Function<T, Double> createNumberExtractor() {
            return t -> {
                final String string = fieldExtractor.apply(t);
                if (string == null) {
                    return null;
                }
                try {
                    return new BigDecimal(string).doubleValue();
                } catch (final RuntimeException e) {
                    return null;
                }
            };
        }

        @Override
        public FieldType getFieldType() {
            return FieldType.TEXT;
        }
    }
}
