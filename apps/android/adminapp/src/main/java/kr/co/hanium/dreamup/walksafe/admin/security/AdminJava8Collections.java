package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime collection factories that are available on Android API 26. */
final class AdminJava8Collections {
    private AdminJava8Collections() {}

    @SafeVarargs
    static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values)));
    }

    static <T> List<T> copyList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    @SafeVarargs
    static <T> Set<T> set(T... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    static <K, V> Map<K, V> map() {
        return Collections.emptyMap();
    }

    static <K, V> Map<K, V> map(K key, V value) {
        return Collections.singletonMap(key, value);
    }

    static <K, V> Map<K, V> map(K key1, V value1, K key2, V value2) {
        Map<K, V> result = new LinkedHashMap<>();
        result.put(key1, value1);
        result.put(key2, value2);
        return Collections.unmodifiableMap(result);
    }

    static <K, V> Map<K, V> map(
        K key1, V value1,
        K key2, V value2,
        K key3, V value3,
        K key4, V value4
    ) {
        Map<K, V> result = new LinkedHashMap<>();
        result.put(key1, value1);
        result.put(key2, value2);
        result.put(key3, value3);
        result.put(key4, value4);
        return Collections.unmodifiableMap(result);
    }

    static <K, V> Map<K, V> copyMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
