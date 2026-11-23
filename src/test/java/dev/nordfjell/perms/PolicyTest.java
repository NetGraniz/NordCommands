package dev.nordfjell.perms;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyTest {
    private final PolicyLoader loader = new PolicyLoader();
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String VALID = """
        schema-version: 1
        groups:
          players:
            permissions: {test.play: true, test.admin: false}
          moderators:
            permissions: {test.admin: true}
        members:
          "00000000-0000-0000-0000-000000000001": moderators
        """;

    @Test void moderatorRightsRequireTrustedAuthentication() {
        Policy policy = loader.parse(VALID);
        assertTrue(policy.permissions(STAFF, true).get("test.admin"));
        assertFalse(policy.permissions(STAFF, false).get("test.admin"));
        assertFalse(policy.permissions(UUID.randomUUID(), true).get("test.admin"));
    }

    @Test void malformedOrBroadPoliciesAreRejected() {
        for (String text : List.of(VALID.replace("schema-version: 1", "schema-version: 2"),
                VALID + "unknown: true\n", VALID.replace("test.play", "test.*"),
                VALID.replace("test.play: true", "test.play: 'true'"), VALID.replace(STAFF.toString(), "Bob")))
            assertThrows(RuntimeException.class, () -> loader.parse(text));
    }

    @Test void policySnapshotsAreImmutable() {
        Policy policy = loader.parse(VALID);
        assertThrows(UnsupportedOperationException.class, () -> policy.players().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.members().clear());
    }
}
