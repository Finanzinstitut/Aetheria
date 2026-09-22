package com.aetheria.client.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that world identifiers are safe to use as directory names.
 *
 * <p>The identifier decides which cache directory a world reads and writes, so a value that escapes
 * its directory or collides with another world's would serve one world's terrain to another.
 */
class WorldIdentityTest {

    @Test
    void serverAddressesBecomeSafeNames() {
        assertEquals("eu.minemen.club", WorldIdentity.sanitise("eu.minemen.club"));
        assertEquals("play.example.net_25565", WorldIdentity.sanitise("play.example.net:25565"));
        assertEquals("127.0.0.1", WorldIdentity.sanitise("127.0.0.1"));
    }

    @Test
    void pathSeparatorsAndTraversalAreNeutralised() {
        assertFalse(WorldIdentity.sanitise("../../etc/passwd").contains("/"));
        assertFalse(WorldIdentity.sanitise("..\\..\\windows").contains("\\"));
        assertEquals(WorldIdentity.UNKNOWN, WorldIdentity.sanitise(".."));
        assertEquals(WorldIdentity.UNKNOWN, WorldIdentity.sanitise("."));
    }

    @Test
    void blankNamesFallBackInsteadOfProducingAnEmptyDirectory() {
        assertEquals(WorldIdentity.UNKNOWN, WorldIdentity.sanitise(""));
        assertEquals(WorldIdentity.UNKNOWN, WorldIdentity.sanitise("   "));
    }

    @Test
    void overlongNamesAreTruncatedToAUsableLength() {
        String result = WorldIdentity.sanitise("w".repeat(500));

        assertTrue(result.length() <= 96, "file systems reject very long path components");
        assertFalse(result.isBlank());
    }

    @Test
    void differentWorldsDoNotCollide() {
        // The whole point: two worlds must never share a cache directory, since chunk coordinates
        // repeat across every world in existence.
        assertFalse(WorldIdentity.sanitise("eu.minemen.club")
                .equals(WorldIdentity.sanitise("us.minemen.club")));
        assertFalse(WorldIdentity.sanitise("My World")
                .equals(WorldIdentity.sanitise("My Other World")));
    }

    @Test
    void spacesAndPunctuationBecomePlaceholders() {
        String result = WorldIdentity.sanitise("Meine Welt (neu)");

        assertFalse(result.contains(" "));
        assertFalse(result.contains("("));
        assertFalse(result.isBlank());
    }
}
