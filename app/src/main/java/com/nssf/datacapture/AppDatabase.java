package com.nssf.datacapture;

import androidx.room.Database;
import androidx.room.RoomDatabase;

/**
 * AppDatabase
 * 
 * Room database for encrypted offline member record storage.
 */
@Database(entities = {MemberRecord.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MemberRecordDao memberRecordDao();
}
