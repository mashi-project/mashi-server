package com.mashiverse.data.db.daos

import com.mashiverse.data.db.PostgresManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException

class UserDao {

    private fun getConnection(): Connection = PostgresManager.dataSource().connection
    private val queries by lazy {
        Queries()
    }

    init {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(queries.createTable)
            }
            conn.commit()
        }
    }

    suspend fun connectWallet(userId: Long, wallet: String): Unit = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            try {
                conn.prepareStatement(queries.upsertUserWallet).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.setString(2, wallet)
                    stmt.executeUpdate()
                    conn.commit()
                }
            } catch (e: SQLException) {
                conn.rollback()
                // PostgreSQL error state 23505 = UNIQUE_VIOLATION
                if (e.sqlState == "23505") {
                    throw IllegalArgumentException("Wallet address '$wallet' is already registered.")
                }
                throw e
            }
        }
    }

    suspend fun disconnectWallet(userId: Long): Unit = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.deleteUser).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeUpdate()
                conn.commit()
            }
        }
    }

    suspend fun getWallet(userId: Long): String? = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectWalletById).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("wallet") else null
                }
            }
        }
    }

    /**
     * Retrieves the user ID associated with the given wallet address.
     * Returns null if the wallet doesn't exist in the database.
     */
    suspend fun getIdByWallet(wallet: String): Long? = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectIdByWallet).use { stmt ->
                stmt.setString(1, wallet)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("id") else null
                }
            }
        }
    }

    suspend fun isExist(wallet: String): Boolean = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.checkWalletExists).use { stmt ->
                stmt.setString(1, wallet)
                stmt.executeQuery().use { rs ->
                    rs.next()
                }
            }
        }
    }

    private class Queries {
        val createTable = """
            CREATE TABLE IF NOT EXISTS users (
                id BIGINT PRIMARY KEY,
                wallet VARCHAR(128) NOT NULL UNIQUE,
                reaction_count INT DEFAULT 0
            );
        """

        val upsertUserWallet = """
            INSERT INTO users (id, wallet) 
            VALUES (?, ?)
            ON CONFLICT (id) 
            DO UPDATE SET wallet = EXCLUDED.wallet;
        """

        val deleteUser = "DELETE FROM users WHERE id = ?"
        val selectWalletById = "SELECT wallet FROM users WHERE id = ?"
        val selectIdByWallet = "SELECT id FROM users WHERE wallet = ?"
        val checkWalletExists = "SELECT 1 FROM users WHERE wallet = ?"
    }
}