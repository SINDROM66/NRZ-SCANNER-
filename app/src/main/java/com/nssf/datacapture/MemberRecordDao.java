package com.nssf.datacapture;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * MemberRecordDao
 * 
 * Data Access Object for encrypted member records.
 */
@Dao
public interface MemberRecordDao {

    @Insert
    long insert(MemberRecord record);

    @Update
    void update(MemberRecord record);

    @Delete
    void delete(MemberRecord record);

    @Query("SELECT * FROM member_records ORDER BY created_at DESC")
    List<MemberRecord> getAll();

    @Query("SELECT * FROM member_records WHERE sync_status = 'PENDING' ORDER BY created_at DESC")
    List<MemberRecord> getPendingSync();

    @Query("SELECT * FROM member_records WHERE sync_status = 'FAILED' ORDER BY created_at DESC")
    List<MemberRecord> getFailedSync();

    @Query("SELECT COUNT(*) FROM member_records")
    int getCount();

    @Query("DELETE FROM member_records WHERE created_at < :cutoffMillis")
    void purgeOldRecords(long cutoffMillis);

    @Query("UPDATE member_records SET sync_status = :status WHERE id = :id")
    void updateSyncStatus(int id, String status);

    @Query("DELETE FROM member_records")
    void deleteAll();
}
