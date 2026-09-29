package com.mashiverse.data.db.daos

import com.mashiverse.data.db.PostgresManager
import data.db.entities.HistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class HistoryDao {

    private fun getConnection() = PostgresManager.dataSource().connection
    private val queries by lazy { Queries() }

    init {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(queries.createTable)
            }
            conn.commit()
        }
    }

    suspend fun addHistory(
        id: UUID = UUID.randomUUID(),
        wallet: String,
        image: ByteArray,
        timestamp: Instant = Instant.now()
    ): Unit = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.insertData).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, wallet)
                stmt.setBytes(3, image)
                stmt.setTimestamp(4, Timestamp.from(timestamp))
                stmt.executeUpdate()
                conn.commit()
            }
        }
    }

    suspend fun getHistoryById(id: UUID): HistoryRecord? = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectById).use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        HistoryRecord(
                            id = rs.getObject("id", UUID::class.java),
                            wallet = rs.getString("wallet"),
                            image = rs.getBytes("image"),
                            timestamp = rs.getTimestamp("timestamp")?.toInstant() ?: Instant.now()
                        )
                    } else null
                }
            }
        }
    }

    suspend fun getHistoryByWallet(wallet: String): List<HistoryRecord> = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectByWallet).use { stmt ->
                stmt.setString(1, wallet)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<HistoryRecord>()
                    while (rs.next()) {
                        results.add(
                            HistoryRecord(
                                id = rs.getObject("id", UUID::class.java),
                                wallet = rs.getString("wallet"),
                                image = rs.getBytes("image"),
                                timestamp = rs.getTimestamp("timestamp")?.toInstant() ?: Instant.now()
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    suspend fun getHistoryByWalletPaginated(wallet: String, limit: Int, offset: Int): List<HistoryRecord> = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectByWalletPaginated).use { stmt ->
                stmt.setString(1, wallet)
                stmt.setInt(2, limit)
                stmt.setInt(3, offset)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<HistoryRecord>()
                    while (rs.next()) {
                        results.add(
                            HistoryRecord(
                                id = rs.getObject("id", UUID::class.java),
                                wallet = rs.getString("wallet"),
                                image = rs.getBytes("image"),
                                timestamp = rs.getTimestamp("timestamp")?.toInstant() ?: Instant.now()
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    suspend fun deleteHistory(id: UUID): Unit = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.deleteData).use { stmt ->
                stmt.setObject(1, id)
                stmt.executeUpdate()
                conn.commit()
            }
        }
    }

    suspend fun deleteHistoryByWallet(wallet: String): Unit = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.deleteByWalletData).use { stmt ->
                stmt.setString(1, wallet)
                stmt.executeUpdate()
                conn.commit()
            }
        }
    }

    private class Queries {
        val createTable = """
            CREATE TABLE IF NOT EXISTS history (
                id UUID PRIMARY KEY,
                wallet VARCHAR(255) NOT NULL,
                image BYTEA,
                timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
        """

        val insertData = """
            INSERT INTO history (id, wallet, image, timestamp) VALUES (?, ?, ?, ?);
        """

        val selectById = """
            SELECT id, wallet, image, timestamp FROM history WHERE id = ?;
        """

        val selectByWallet = """
            SELECT id, wallet, image, timestamp FROM history WHERE wallet = ? ORDER BY timestamp DESC;
        """

        val selectByWalletPaginated = """
            SELECT id, wallet, image, timestamp FROM history WHERE wallet = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?;
        """

        val deleteData = """
            DELETE FROM history WHERE id = ?;
        """

        val deleteByWalletData = """
            DELETE FROM history WHERE wallet = ?;
        """
    }
}