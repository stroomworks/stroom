package stroom.planb.impl.db.serde.valtime;

import stroom.query.language.functions.Val;

import java.time.Instant;

public record ValTime(Val val, Instant insertTime) {

}
