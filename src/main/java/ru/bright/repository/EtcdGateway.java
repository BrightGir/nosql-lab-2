package ru.bright.repository;

import java.util.List;
import java.util.Optional;

public interface EtcdGateway {
    Optional<StoredValue> get(String key);
    List<StoredValue> getPrefix(String prefix);
    void put(String key, String value);
    void putWithTtl(String key, String value, long ttlSeconds);
    boolean compareAndSet(String key, long expectedRevision, String value);
    void delete(String key);
}
