package stroom.query.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParamBuilderTest {
    @Test
    void doesBuild() {
        final String key = "someKey";
        final String value = "someValue";

        final Param param = Param
                .builder()
                .key(key)
                .value(value)
                .build();

        assertThat(param.getKey()).isEqualTo(key);
        assertThat(param.getValue()).isEqualTo(value);
    }
}
