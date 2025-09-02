package io.projectenv.tools;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

public final class SortedCollections {

    private SortedCollections() {
        // noop
    }


    @SafeVarargs
    public static <V extends Comparable<? extends V>> SortedSet<V> createNaturallySortedSet(V... initialEntries) {
        return createNaturallySortedSet(List.of(initialEntries));
    }

    public static <V extends Comparable<? extends V>> SortedSet<V> createNaturallySortedSet(Collection<V> initialEntries) {
        SortedSet<V> set = createNaturallySortedSet();
        if (initialEntries != null) {
            set.addAll(initialEntries);
        }

        return set;
    }

    public static <V extends Comparable<? extends V>> SortedSet<V> createNaturallySortedSet() {
        return new TreeSet<>();
    }

    public static <V extends Comparable<? extends V>> Collector<V, SortedSet<V>, SortedSet<V>> toNaturallySortedSet() {
        return new Collector<>() {
            @Override
            public Supplier<SortedSet<V>> supplier() {
                return SortedCollections::createNaturallySortedSet;
            }

            @Override
            public BiConsumer<SortedSet<V>, V> accumulator() {
                return SortedSet::add;
            }

            @Override
            public BinaryOperator<SortedSet<V>> combiner() {
                return (left, right) -> {
                    if (left.size() < right.size()) {
                        right.addAll(left);
                        return right;
                    } else {
                        left.addAll(right);
                        return left;
                    }
                };
            }

            @Override
            public Function<SortedSet<V>, SortedSet<V>> finisher() {
                return value -> value;
            }

            @Override
            public Set<Collector.Characteristics> characteristics() {
                return Collections.emptySet();
            }
        };
    }

    public static <K extends Comparable<? extends K>, V> SortedMap<K, V> createNaturallySortedMap(Map<K, V> initialEntries) {
        SortedMap<K, V> map = createNaturallySortedMap();
        if (initialEntries != null) {
            map.putAll(initialEntries);
        }

        return map;
    }

    public static <K extends Comparable<? extends K>, V> SortedMap<K, V> createNaturallySortedMap() {
        return new TreeMap<>();
    }

    public static <V> SortedMap<String, V> createSemverSortedMap(Map<String, V> initialEntries) {
        SortedMap<String, V> map = createSemverSortedMap();
        if (initialEntries != null) {
            map.putAll(initialEntries);
        }

        return map;
    }

    public static <V> SortedMap<String, V> createSemverSortedMap() {
        return new TreeMap<>(SortedCollections::compareVersions);
    }

    public static int compareVersions(String version1, String version2) {
        // Remove build metadata (+...) for both versions
        String v1 = version1.split("\\+")[0];
        String v2 = version2.split("\\+")[0];

        // Extract rc suffix if present
        String[] v1Parts = v1.split("-rc-");
        String[] v2Parts = v2.split("-rc-");
        String v1Main = v1Parts[0];
        String v2Main = v2Parts[0];
        Integer v1Rc = v1Parts.length > 1 ? parseIntSafe(v1Parts[1]) : null;
        Integer v2Rc = v2Parts.length > 1 ? parseIntSafe(v2Parts[1]) : null;

        // Compare main version numbers
        String[] v1Splits = v1Main.split("\\.");
        String[] v2Splits = v2Main.split("\\.");
        int maxLength = Math.max(v1Splits.length, v2Splits.length);
        for (int i = 0; i < maxLength; i++) {
            int n1 = i < v1Splits.length ? parseIntSafe(v1Splits[i]) : 0;
            int n2 = i < v2Splits.length ? parseIntSafe(v2Splits[i]) : 0;
            int cmp = Integer.compare(n1, n2);
            if (cmp != 0) {
                return cmp;
            }
        }

        // If main versions are equal, handle rc suffix
        if (v1Rc != null && v2Rc != null) {
            return Integer.compare(v1Rc, v2Rc);
        } else if (v1Rc != null) {
            // rc is lower precedence than final
            return -1;
        } else if (v2Rc != null) {
            return 1;
        }

        // If still equal, consider versions equal
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}