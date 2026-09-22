package com.example.childsafetyimpl.di

import android.content.Context
import androidx.room.Room
import com.example.childsafetyimpl.data.local.AppRuleDao
import com.example.childsafetyimpl.data.local.CategoryBudgetDao
import com.example.childsafetyimpl.data.local.CommunicationSignalDao
import com.example.childsafetyimpl.data.local.DailyUsageDao
import com.example.childsafetyimpl.data.local.DomainRuleDao
import com.example.childsafetyimpl.data.local.AiInteractionDao
import com.example.childsafetyimpl.data.local.CloneFindingDao
import com.example.childsafetyimpl.data.local.SurfaceEncounterDao
import com.example.childsafetyimpl.data.local.SafetyDatabase
import com.example.childsafetyimpl.data.local.SafetyEventDao
import com.example.childsafetyimpl.data.local.WebCategoryDao
import com.example.childsafetyimpl.data.source.SeedDomainRules
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        databaseProvider: Provider<SafetyDatabase>,
    ): SafetyDatabase = Room
        .databaseBuilder(context, SafetyDatabase::class.java, SafetyDatabase.NAME)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onCreate(connection: androidx.sqlite.SQLiteConnection) {
                super.onCreate(connection)
                // Seeded on first open so the filter has rules to evaluate before the parent
                // configures anything. Done off the creation callback thread deliberately.
                scope.launch {
                    val db = databaseProvider.get()
                    db.domainRuleDao().insertAll(SeedDomainRules.rules)
                    db.webCategoryDao().insertAll(SeedDomainRules.categoryDefaults)
                }
            }
        })
        .build()

    @Provides fun provideSafetyEventDao(db: SafetyDatabase): SafetyEventDao = db.safetyEventDao()
    @Provides fun provideAppRuleDao(db: SafetyDatabase): AppRuleDao = db.appRuleDao()
    @Provides fun provideCategoryBudgetDao(db: SafetyDatabase): CategoryBudgetDao = db.categoryBudgetDao()
    @Provides fun provideDomainRuleDao(db: SafetyDatabase): DomainRuleDao = db.domainRuleDao()
    @Provides fun provideWebCategoryDao(db: SafetyDatabase): WebCategoryDao = db.webCategoryDao()
    @Provides fun provideDailyUsageDao(db: SafetyDatabase): DailyUsageDao = db.dailyUsageDao()
    @Provides fun provideAiInteractionDao(db: SafetyDatabase): AiInteractionDao = db.aiInteractionDao()
    @Provides
    fun provideSurfaceEncounterDao(db: SafetyDatabase): SurfaceEncounterDao = db.surfaceEncounterDao()
    @Provides fun provideCloneFindingDao(db: SafetyDatabase): CloneFindingDao = db.cloneFindingDao()
    @Provides
    fun provideCommunicationSignalDao(db: SafetyDatabase): CommunicationSignalDao =
        db.communicationSignalDao()
}
