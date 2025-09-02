package io.projectenv.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SortedCollectionsTest {
    @Test
    void testStandardVersions() {
        assertTrue(SortedCollections.compareVersions("1.2.3", "1.2.4") < 0);
        assertTrue(SortedCollections.compareVersions("1.2.4", "1.2.3") > 0);
        assertEquals(0, SortedCollections.compareVersions("1.2.3", "1.2.3"));
    }

    @Test
    void testRcSuffixVsFinal() {
        assertTrue(SortedCollections.compareVersions("1.2.3-rc-1", "1.2.3") < 0);
        assertTrue(SortedCollections.compareVersions("1.2.3", "1.2.3-rc-1") > 0);
    }

    @Test
    void testBothRcSuffix() {
        assertTrue(SortedCollections.compareVersions("1.2.3-rc-1", "1.2.3-rc-2") < 0);
        assertTrue(SortedCollections.compareVersions("1.2.3-rc-2", "1.2.3-rc-1") > 0);
        assertEquals(0, SortedCollections.compareVersions("1.2.3-rc-1", "1.2.3-rc-1"));
    }

    @Test
    void testBuildMetadataIgnored() {
        assertEquals(0, SortedCollections.compareVersions("1.2.3+build1", "1.2.3+build2"));
        assertTrue(SortedCollections.compareVersions("1.2.3+build1", "1.2.4+build2") < 0);
    }

    @Test
    void testMissingPatch() {
        assertEquals(0, SortedCollections.compareVersions("1.2", "1.2.0"));
        assertTrue(SortedCollections.compareVersions("1.2", "1.2.1") < 0);
    }

    @Test
    void testLeadingZeros() {
        assertEquals(0, SortedCollections.compareVersions("01.02.03", "1.2.3"));
        assertTrue(SortedCollections.compareVersions("1.02.3", "1.2.4") < 0);
    }

    @Test
    void testDifferentLengths() {
        assertTrue(SortedCollections.compareVersions("1.2.3", "1.2.3.1") < 0);
        assertTrue(SortedCollections.compareVersions("1.2.3.1", "1.2.3") > 0);
    }

    @Test
    void testEdgeCases() {
        assertEquals(0, SortedCollections.compareVersions("1.0.0", "1.0.0"));
        assertTrue(SortedCollections.compareVersions("1.0.0-rc-1", "1.0.0-rc-2") < 0);
        assertTrue(SortedCollections.compareVersions("1.0.0-rc-2", "1.0.0") < 0);
    }

    @Test
    void testPartsWithTwoDecimals() {
        assertTrue(SortedCollections.compareVersions("1.2", "1.10") < 0);
        assertTrue(SortedCollections.compareVersions("2.9", "2.10") < 0);
    }
}

