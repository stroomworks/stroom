package stroom.query.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OffsetRangeTest {
    @Test
    void doesBuild() {
        final Long offset = 30L;
        final Long length = 1000L;

        final OffsetRange offsetRange = OffsetRange
                .builder()
                .offset(offset)
                .length(length)
                .build();

        assertThat(offsetRange.getOffset()).isEqualTo(offset);
        assertThat(offsetRange.getLength()).isEqualTo(length);
    }
}
