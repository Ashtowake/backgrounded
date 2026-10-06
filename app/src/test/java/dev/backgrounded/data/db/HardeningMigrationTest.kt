package dev.backgrounded.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HardeningMigrationTest {
    @Test fun `version twelve cold opens with strict version thirteen validation`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "migration-hardening.db"
            context.deleteDatabase(name)
            val schema =
                Json.parseToJsonElement(
                    File("schemas/dev.backgrounded.data.db.BackgroundedDatabase/12.json")
                        .readText(),
                ).jsonObject.getValue("database").jsonObject
            SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(name).apply { parentFile?.mkdirs() },
                null,
            ).use {
                    old ->
                schema.getValue("entities").jsonArray.forEach { entity ->
                    val data = entity.jsonObject
                    val table = data.getValue("tableName").jsonPrimitive.content
                    old.execSQL(data.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                    data["indices"]?.jsonArray?.forEach { index ->
                        old.execSQL(
                            index.jsonObject.getValue("createSql").jsonPrimitive.content
                                .replace("${'$'}{TABLE_NAME}", table),
                        )
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
                old.version = 12
            }
            val db =
                Room.databaseBuilder(context, BackgroundedDatabase::class.java, name)
                    .addMigrations(BackgroundedDatabase.MIGRATION_12_13).build()
            try {
                assertEquals(0, db.historyDao().count())
                assertEquals(0, db.hardeningDao().pendingOperations())
                db.hardeningDao().recordDocument(ScanDocumentEntity(1, "document", 10, 20, "DUPLICATE"))
                assertEquals("DUPLICATE", db.hardeningDao().document(1, "document")?.status)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
}
