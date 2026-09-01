package com.solarized.firedown.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.solarized.firedown.data.entity.WasmAllowlistEntity;

import java.util.List;

@Dao
public interface WasmAllowlistDao {

    @Query("SELECT uid FROM wasm_allowlist")
    List<Integer> getAllIds();

    // @Transaction: an unbounded read can span several CursorWindows and
    // must pin one snapshot — see DownloadDao "One-shot Queries".
    @Transaction
    @Query("SELECT * FROM wasm_allowlist ORDER BY date DESC")
    List<WasmAllowlistEntity> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(WasmAllowlistEntity entity);

    @Query("DELETE FROM wasm_allowlist WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM wasm_allowlist")
    Integer deleteAll();
}
