package stroom.query.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SortBuilderTest {
    @Test
    void doesBuild() {
        final Sort.SortDirection direction = Sort.SortDirection.DESCENDING;
        final Integer order = 3;

        final Sort sort = Sort
                .builder()
                .direction(direction)
                .order(order)
                .build();

        assertThat(sort.getDirection()).isEqualTo(direction);
        assertThat(sort.getOrder()).isEqualTo(order);
    }
}
