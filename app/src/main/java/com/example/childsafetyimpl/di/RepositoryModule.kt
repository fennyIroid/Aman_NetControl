package com.example.childsafetyimpl.di

import com.example.childsafetyimpl.data.repository.CommunicationRepositoryImpl
import com.example.childsafetyimpl.data.repository.SafetyEventRepositoryImpl
import com.example.childsafetyimpl.data.repository.SafetyRulesRepositoryImpl
import com.example.childsafetyimpl.domain.repository.CommunicationRepository
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindSafetyEventRepository(impl: SafetyEventRepositoryImpl): SafetyEventRepository

    @Binds @Singleton
    abstract fun bindSafetyRulesRepository(impl: SafetyRulesRepositoryImpl): SafetyRulesRepository

    @Binds @Singleton
    abstract fun bindCommunicationRepository(impl: CommunicationRepositoryImpl): CommunicationRepository
}
