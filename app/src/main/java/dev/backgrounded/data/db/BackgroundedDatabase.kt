package dev.backgrounded.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlbumEntity::class,
        BackgroundEntity::class,
        BackgroundPairEntity::class,
        BackgroundFramingEntity::class,
        HistoryEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class BackgroundedDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao

    abstract fun backgroundDao(): BackgroundDao

    abstract fun pairDao(): BackgroundPairDao

    abstract fun framingDao(): FramingDao

    abstract fun historyDao(): HistoryDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `backgrounds_new` (" +
                            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "`albumId` INTEGER NOT NULL, " +
                            "`sourceType` TEXT NOT NULL, " +
                            "`storageRef` TEXT NOT NULL, " +
                            "`displayName` TEXT NOT NULL, " +
                            "`sha256` TEXT, " +
                            "`width` INTEGER NOT NULL, " +
                            "`height` INTEGER NOT NULL, " +
                            "`dimForLock` INTEGER NOT NULL, " +
                            "`sortIndex` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "INSERT INTO `backgrounds_new` " +
                            "(`id`, `albumId`, `sourceType`, `storageRef`, `displayName`, `sha256`, " +
                            "`width`, `height`, `dimForLock`, `sortIndex`, `addedAt`) " +
                            "SELECT `id`, `albumId`, `sourceType`, `storageRef`, `displayName`, `sha256`, " +
                            "`width`, `height`, `dimForLock`, `sortIndex`, `addedAt` FROM `backgrounds`",
                    )
                    db.execSQL("ALTER TABLE `backgrounds` RENAME TO `backgrounds_old`")
                    db.execSQL("ALTER TABLE `backgrounds_new` RENAME TO `backgrounds`")
                    db.execSQL("DROP INDEX IF EXISTS `index_backgrounds_albumId`")
                    db.execSQL("DROP INDEX IF EXISTS `index_backgrounds_sha256`")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_backgrounds_albumId` " +
                            "ON `backgrounds` (`albumId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_backgrounds_sha256` " +
                            "ON `backgrounds` (`sha256`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `background_framings` (" +
                            "`backgroundId` INTEGER NOT NULL, " +
                            "`target` TEXT NOT NULL, " +
                            "`fitMode` TEXT NOT NULL, " +
                            "`cropLeft` REAL NOT NULL, " +
                            "`cropTop` REAL NOT NULL, " +
                            "`cropWidth` REAL NOT NULL, " +
                            "`cropHeight` REAL NOT NULL, " +
                            "`zoom` REAL NOT NULL, " +
                            "`panX` REAL NOT NULL, " +
                            "`panY` REAL NOT NULL, " +
                            "`rotationDegrees` INTEGER NOT NULL, " +
                            "`backdrop` TEXT NOT NULL, " +
                            "`blurIntensity` INTEGER NOT NULL, " +
                            "`backdropZoom` REAL NOT NULL, " +
                            "`backdropPanX` REAL NOT NULL, " +
                            "`backdropPanY` REAL NOT NULL, " +
                            "`backdropColor` INTEGER NOT NULL, " +
                            "`scrollMode` TEXT NOT NULL, " +
                            "`scrollAmountPercent` INTEGER NOT NULL, " +
                            "`scrollPages` INTEGER NOT NULL, " +
                            "`scrollStartFraction` REAL NOT NULL, " +
                            "`scrollSpanFraction` REAL NOT NULL, " +
                            "PRIMARY KEY(`backgroundId`, `target`), " +
                            "FOREIGN KEY(`backgroundId`) REFERENCES `backgrounds`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_background_framings_backgroundId` " +
                            "ON `background_framings` (`backgroundId`)",
                    )
                    listOf("INNER", "COVER").forEach { target ->
                        db.execSQL(
                            "INSERT INTO `background_framings` " +
                                "(`backgroundId`, `target`, `fitMode`, `cropLeft`, `cropTop`, `cropWidth`, " +
                                "`cropHeight`, `zoom`, `panX`, `panY`, `rotationDegrees`, `backdrop`, " +
                                "`blurIntensity`, `backdropZoom`, `backdropPanX`, `backdropPanY`, " +
                                "`backdropColor`, `scrollMode`, `scrollAmountPercent`, `scrollPages`, " +
                                "`scrollStartFraction`, `scrollSpanFraction`) " +
                                "SELECT `id`, '$target', `fitMode`, `cropLeft`, `cropTop`, `cropWidth`, " +
                                "`cropHeight`, `zoom`, `panX`, `panY`, `rotationDegrees`, `backdrop`, " +
                                "`blurIntensity`, `backdropZoom`, `backdropPanX`, `backdropPanY`, " +
                                "`backdropColor`, 'AMOUNT', " +
                                "CAST(ROUND(`parallaxAmount` * 100) AS INTEGER), 3, 0.0, 1.0 " +
                                "FROM `backgrounds_old`",
                        )
                    }
                    db.execSQL("DROP TABLE `backgrounds_old`")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `background_framings_new` (" +
                            "`backgroundId` INTEGER NOT NULL, " +
                            "`target` TEXT NOT NULL, " +
                            "`surface` TEXT NOT NULL, " +
                            "`fitMode` TEXT NOT NULL, " +
                            "`cropLeft` REAL NOT NULL, " +
                            "`cropTop` REAL NOT NULL, " +
                            "`cropWidth` REAL NOT NULL, " +
                            "`cropHeight` REAL NOT NULL, " +
                            "`zoom` REAL NOT NULL, " +
                            "`panX` REAL NOT NULL, " +
                            "`panY` REAL NOT NULL, " +
                            "`rotationDegrees` INTEGER NOT NULL, " +
                            "`backdrop` TEXT NOT NULL, " +
                            "`blurIntensity` INTEGER NOT NULL, " +
                            "`backdropZoom` REAL NOT NULL, " +
                            "`backdropPanX` REAL NOT NULL, " +
                            "`backdropPanY` REAL NOT NULL, " +
                            "`backdropColor` INTEGER NOT NULL, " +
                            "`scrollMode` TEXT NOT NULL, " +
                            "`scrollAmountPercent` INTEGER NOT NULL, " +
                            "`scrollPages` INTEGER NOT NULL, " +
                            "`scrollStartFraction` REAL NOT NULL, " +
                            "`scrollSpanFraction` REAL NOT NULL, " +
                            "PRIMARY KEY(`backgroundId`, `target`, `surface`), " +
                            "FOREIGN KEY(`backgroundId`) REFERENCES `backgrounds`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    listOf("HOME", "LOCK").forEach { surface ->
                        db.execSQL(
                            "INSERT INTO `background_framings_new` " +
                                "(`backgroundId`, `target`, `surface`, `fitMode`, `cropLeft`, `cropTop`, " +
                                "`cropWidth`, `cropHeight`, `zoom`, `panX`, `panY`, `rotationDegrees`, " +
                                "`backdrop`, `blurIntensity`, `backdropZoom`, `backdropPanX`, " +
                                "`backdropPanY`, `backdropColor`, `scrollMode`, `scrollAmountPercent`, " +
                                "`scrollPages`, `scrollStartFraction`, `scrollSpanFraction`) " +
                                "SELECT `backgroundId`, `target`, '$surface', `fitMode`, `cropLeft`, " +
                                "`cropTop`, `cropWidth`, `cropHeight`, `zoom`, `panX`, `panY`, " +
                                "`rotationDegrees`, `backdrop`, `blurIntensity`, `backdropZoom`, " +
                                "`backdropPanX`, `backdropPanY`, `backdropColor`, `scrollMode`, " +
                                "`scrollAmountPercent`, `scrollPages`, `scrollStartFraction`, " +
                                "`scrollSpanFraction` FROM `background_framings`",
                        )
                    }
                    db.execSQL("DROP TABLE `background_framings`")
                    db.execSQL("ALTER TABLE `background_framings_new` RENAME TO `background_framings`")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_background_framings_backgroundId` " +
                            "ON `background_framings` (`backgroundId`)",
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `background_framings` ADD COLUMN `stretchX` REAL NOT NULL DEFAULT 1.0",
                    )
                    db.execSQL(
                        "ALTER TABLE `background_framings` ADD COLUMN `stretchY` REAL NOT NULL DEFAULT 1.0",
                    )
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `background_pairs` (" +
                            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "`albumId` INTEGER NOT NULL, " +
                            "`homeBackgroundId` INTEGER NOT NULL, " +
                            "`lockBackgroundId` INTEGER NOT NULL, " +
                            "`sortIndex` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`homeBackgroundId`) REFERENCES `backgrounds`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`lockBackgroundId`) REFERENCES `backgrounds`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_background_pairs_albumId` " +
                            "ON `background_pairs` (`albumId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_background_pairs_homeBackgroundId` " +
                            "ON `background_pairs` (`homeBackgroundId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_background_pairs_lockBackgroundId` " +
                            "ON `background_pairs` (`lockBackgroundId`)",
                    )
                    db.execSQL(
                        "INSERT INTO `background_pairs` " +
                            "(`id`, `albumId`, `homeBackgroundId`, `lockBackgroundId`, `sortIndex`, `addedAt`) " +
                            "SELECT `id`, `albumId`, `id`, `id`, `sortIndex`, `addedAt` FROM `backgrounds`",
                    )
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `albums` ADD COLUMN `fixedHomeAssetId` INTEGER")
                    db.execSQL("ALTER TABLE `albums` ADD COLUMN `fixedLockAssetId` INTEGER")
                }
            }

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `background_framings` ADD COLUMN `gyroParallax` " +
                            "INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE `background_framings` ADD COLUMN `gyroIntensity` " +
                            "INTEGER NOT NULL DEFAULT 50",
                    )
                }
            }
    }
}
