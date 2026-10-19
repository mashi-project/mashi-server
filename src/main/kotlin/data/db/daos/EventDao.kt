package com.mashiverse.data.db.daos

import com.mashiverse.data.db.PostgresManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EventDao {

    private fun getConnection() = PostgresManager.dataSource().connection
    private val queries by lazy { Queries() }

    init {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(queries.createTable)
                stmt.executeUpdate(queries.createUserIndex)
            }
            conn.commit()
        }
    }

    /**
     * Inserts the event only if [id] doesn't exist yet.
     * @return true if a row was added, false if the id already existed.
     */
    suspend fun addIfAbsent(id: Int, userId: Long): Boolean = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.insertIfAbsent).use { stmt ->
                stmt.setInt(1, id)
                stmt.setLong(2, userId)
                val rows = stmt.executeUpdate()
                conn.commit()
                rows > 0
            }
        }
    }

    /**
     * @return true if [userId] already has at least one row in the table.
     */
    suspend fun existsByUserId(userId: Long): Boolean = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.existsByUserId).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs ->
                    rs.next() && rs.getBoolean(1)
                }
            }
        }
    }

    private class Queries {
        val createTable = """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY,
                user_id BIGINT NOT NULL
            );
        """

        val createUserIndex = """
            CREATE INDEX IF NOT EXISTS idx_events_user_id ON events (user_id);
        """

        val insertIfAbsent = """
            INSERT INTO events (id, user_id) VALUES (?, ?)
            ON CONFLICT (id) DO NOTHING;
        """

        val existsByUserId = """
            SELECT EXISTS (SELECT 1 FROM events WHERE user_id = ?);
        """
    }
}