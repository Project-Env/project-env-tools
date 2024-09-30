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

    private static int compareVersions(String version1, String version2) {
        int comparisonResult = 0;

        String[] version1Splits = version1.split("[.+]");
        String[] version2Splits = version2.split("[.+]");
        int maxLengthOfVersionSplits = Math.max(version1Splits.length, version2Splits.length);

        for (int i = 0; i < maxLengthOfVersionSplits; i++) {
            Integer v1 = i < version1Splits.length ? Integer.parseInt(version1Splits[i]) : 0;
            Integer v2 = i < version2Splits.length ? Integer.parseInt(version2Splits[i]) : 0;
            int compare = v1.compareTo(v2);
            if (compare != 0) {
                comparisonResult = compare;
                break;
            }
        }
        return comparisonResult;
    }

}