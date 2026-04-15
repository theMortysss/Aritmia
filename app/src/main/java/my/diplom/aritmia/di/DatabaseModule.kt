package my.diplom.aritmia.di

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.nn.NetworkRepository
import javax.inject.Singleton

@Module
@RequiresApi(Build.VERSION_CODES.O)
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .build()

    @Provides fun provideUserDao(db: AppDatabase)    = db.userDao()
    @Provides fun provideSymptomDao(db: AppDatabase) = db.symptomDao()
    @Provides fun provideRuleDao(db: AppDatabase)    = db.ruleDao()

    @Provides
    @Singleton
    fun provideNetworkRepository(@ApplicationContext context: Context): NetworkRepository =
        NetworkRepository(context)
}
