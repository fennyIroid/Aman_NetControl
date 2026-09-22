package com.example.childsafetyimpl.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SafetyEventEntity::class,
        AppRuleEntity::class,
        CategoryBudgetEntity::class,
        DomainRuleEntity::class,
        WebCategorySettingEntity::class,
        DailyUsageEntity::class,
        AiInteractionEntity::class,
        SurfaceEncounterEntity::class,
        CloneFindingEntity::class,
        CommunicationSignalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(SafetyConverters::class)
abstract class SafetyDatabase : RoomDatabase() {
    abstract fun safetyEventDao(): SafetyEventDao
    abstract fun appRuleDao(): AppRuleDao
    abstract fun categoryBudgetDao(): CategoryBudgetDao
    abstract fun domainRuleDao(): DomainRuleDao
    abstract fun webCategoryDao(): WebCategoryDao
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun aiInteractionDao(): AiInteractionDao
    abstract fun surfaceEncounterDao(): SurfaceEncounterDao
    abstract fun cloneFindingDao(): CloneFindingDao
    abstract fun communicationSignalDao(): CommunicationSignalDao

    companion object {
        const val NAME = "child_safety.db"
    }
}
