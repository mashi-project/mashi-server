package com.mashiverse.data.db.daos

import com.mashiverse.data.db.PostgresManager
import com.mashiverse.data.models.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class ImageDao {

    private fun getConnection(): Connection = PostgresManager.dataSource().connection
    private val queries by lazy {
        Queries()
    }

    init {
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(queries.createTable)
            }
            conn.commit()
        }
    }

    suspend fun addImage(url: String, byteData: ByteArray, type: ImageType): Unit = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(queries.upsertData).use { stmt ->
                stmt.setString(1, url)
                stmt.setBytes(2, byteData)
                stmt.setString(3, type.name)
                stmt.executeUpdate()
                conn.commit()
            }
        }
    }

    suspend fun getImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectData).use { stmt ->
                stmt.setString(1, url)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getBytes("data") else null
                }
            }
        }
    }

    suspend fun getImageType(url: String): ImageType? = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.prepareStatement(queries.selectType).use { stmt ->
                stmt.setString(1, url)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getString("type")?.let { typeName ->
                            runCatching { ImageType.valueOf(typeName) }.getOrNull()
                        }
                    } else null
                }
            }
        }
    }

    private class Queries {
        val createTable = """
            CREATE TABLE IF NOT EXISTS images (
                url VARCHAR(1000) PRIMARY KEY,
                data BYTEA,
                type VARCHAR(255) NOT NULL
            );
        """

        val upsertData = """
            INSERT INTO images (url, data, type) VALUES (?, ?, ?)
            ON CONFLICT (url) DO UPDATE SET data = EXCLUDED.data, type = EXCLUDED.type;
        """

        val selectData = "SELECT data FROM images WHERE url = ?"
        val selectType = "SELECT type FROM images WHERE url = ?"
    }
}