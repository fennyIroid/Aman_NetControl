package com.example.childsafetyimpl.di

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.UserManager
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.platform.content.SubjectDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Platform services are injected rather than fetched inline so each capability module can be
 * exercised and reasoned about in isolation, and so a protection can be tested without standing
 * up the whole app.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun providePreferences(@ApplicationContext context: Context) = SafetyPreferences(context)

    @Provides
    fun providePackageManager(@ApplicationContext context: Context): PackageManager =
        context.packageManager

    @Provides
    fun provideUsageStatsManager(@ApplicationContext context: Context): UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Provides
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Provides
    fun provideAppOpsManager(@ApplicationContext context: Context): AppOpsManager =
        context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

    @Provides
    fun provideDevicePolicyManager(@ApplicationContext context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    @Provides
    fun provideUserManager(@ApplicationContext context: Context): UserManager =
        context.getSystemService(Context.USER_SERVICE) as UserManager

    @Provides
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Provides
    fun providePowerManager(@ApplicationContext context: Context): PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Provides
    fun provideActivityManager(@ApplicationContext context: Context): ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @Provides
    fun provideMediaProjectionManager(@ApplicationContext context: Context): MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /**
     * The subject detector, with no appearance model bound.
     *
     * Bound here rather than by constructor injection so that this decision is visible in the
     * dependency graph instead of hidden in a default argument. Supplying a [GenderModel] is the
     * single change that turns on the male/female targeting the client asked about, and it should
     * be an explicit edit to this line by someone who has read what
     * [com.example.childsafetyimpl.platform.content.SubjectDetector] says about the accuracy of
     * such a model in this setting.
     *
     * Until then gender targeting degrades to covering every person, which is the safe direction
     * and is tested as such.
     */
    @Provides
    @Singleton
    fun provideSubjectDetector(): SubjectDetector = SubjectDetector(genderModel = null)
}
