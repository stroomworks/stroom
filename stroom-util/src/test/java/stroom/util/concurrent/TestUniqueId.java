package stroom.util.concurrent;

import stroom.util.concurrent.UniqueId.NodeType;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TestUniqueId {

    @Test
    void parse() {
        final UniqueId uniqueId = new UniqueId(123456, 22, NodeType.PROXY, "node-abc");
        final String str = uniqueId.toString();
        final UniqueId uniqueId2 = UniqueId.parse(str);
        Assertions.assertThat(uniqueId2)
                .isEqualTo(uniqueId);
    }

    @Test
    void testToString() {
        final UniqueId uniqueId = new UniqueId(123456, 22, NodeType.STROOM, "node-abc");
        final String str = uniqueId.toString();
        Assertions.assertThat(str)
                .isEqualTo("0000000123456_0022_S_node-abc");
    }

    @Test
    void testToString2() {
        final UniqueId uniqueId = new UniqueId(123456, 5, NodeType.PROXY, "node-abc");
        final String str = uniqueId.toString();
        Assertions.assertThat(str)
                .isEqualTo("0000000123456_0005_P_node-abc");
    }

    @Test
    void testToString3() {
        final UniqueId uniqueId = new UniqueId(
                1748593005000L, // Friday, 30 May 2025 08:16:45
                1234,
                NodeType.PROXY,
                "node-abc");

        final String str = uniqueId.toString();
        Assertions.assertThat(str)
                .isEqualTo("1748593005000_1234_P_node-abc");
    }

    @Test
    void testNodeType() {
        Assertions.assertThat(NodeType.fromString("P"))
                .isEqualTo(NodeType.PROXY);
        Assertions.assertThat(NodeType.fromString("p"))
                .isEqualTo(NodeType.PROXY);
        Assertions.assertThat(NodeType.fromString("S"))
                .isEqualTo(NodeType.STROOM);
        Assertions.assertThat(NodeType.fromString("s"))
                .isEqualTo(NodeType.STROOM);
    }
}
