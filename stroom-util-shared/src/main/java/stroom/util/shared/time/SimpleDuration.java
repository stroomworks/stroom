package stroom.util.shared.time;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public class SimpleDuration {

    public static SimpleDuration ZERO = new SimpleDuration(0, TimeUnit.NANOSECONDS);

    @JsonProperty
    private final long time;
    @JsonProperty
    private final TimeUnit timeUnit;

    @JsonCreator
    public SimpleDuration(@JsonProperty("time") final long time,
                          @JsonProperty("timeUnit") final TimeUnit timeUnit) {
        this.time = time;
        this.timeUnit = timeUnit == null
                ? TimeUnit.DAYS
                : timeUnit;
    }

    public long getTime() {
        return time;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SimpleDuration that = (SimpleDuration) o;
        return time == that.time && timeUnit == that.timeUnit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, timeUnit);
    }

    @Override
    public String toString() {
        return time + timeUnit.getShortForm();
    }

    public String toLongString() {
        return time + " " + timeUnit.getDisplayValue();
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private long time;
        private TimeUnit timeUnit;

        private Builder() {
        }

        private Builder(final SimpleDuration simpleDuration) {
            this.time = simpleDuration.time;
            this.timeUnit = simpleDuration.timeUnit;
        }

        public Builder time(final long time) {
            this.time = time;
            return this;
        }

        public Builder timeUnit(final TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
            return this;
        }

        public SimpleDuration build() {
            if (timeUnit == null) {
                timeUnit = TimeUnit.DAYS;
            }
            return new SimpleDuration(time, timeUnit);
        }
    }
}
