package org.tasks.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface FilterDao {
    @Update
    fun update(filter: Filter)

    @Query("DELETE FROM filters WHERE _id = :id")
    fun delete(id: Long)

    @Query("SELECT * FROM filters WHERE title = :title COLLATE NOCASE LIMIT 1")
    fun getByName(title: String): Filter?

    @Insert
    fun insert(filter: Filter): Long

    @Query("SELECT * FROM filters")
    fun getFilters(): List<Filter>

    @Query("SELECT * FROM filters WHERE _id = :id LIMIT 1")
    fun getById(id: Long): Filter?

    @Query("SELECT * FROM filters")
    fun getAll(): List<Filter>
}