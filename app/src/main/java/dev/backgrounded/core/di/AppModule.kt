package dev.backgrounded.core.di

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.backgrounded.data.db.BackgroundedDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): BackgroundedDatabase =
        Room.databaseBuilder(context, BackgroundedDatabase::class.java, "backgrounded.db")
            .addMigrations(
                BackgroundedDatabase.MIGRATION_1_2,
                BackgroundedDatabase.MIGRATION_2_3,
                BackgroundedDatabase.MIGRATION_3_4,
                BackgroundedDatabase.MIGRATION_4_5,
                BackgroundedDatabase.MIGRATION_5_6,
                BackgroundedDatabase.MIGRATION_6_7,
            )
            .build()

    @Provides
    @Singleton
    fun alarmManager(
        @ApplicationContext context: Context,
    ): AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
