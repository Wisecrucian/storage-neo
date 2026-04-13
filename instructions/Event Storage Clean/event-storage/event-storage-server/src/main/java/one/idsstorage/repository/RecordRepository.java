package one.idsstorage.repository;

import one.idsstorage.domain.Record;

import java.util.List;

public interface RecordRepository {
    void saveAll(List<Record> records);
}
