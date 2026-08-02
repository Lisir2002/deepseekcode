package com.deepseek.coder.di

import com.deepseek.coder.data.workflow.ContextGovernor
import com.deepseek.coder.data.workflow.OrchestratorImpl
import com.deepseek.coder.domain.workflow.Orchestrator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkflowModule {
    @Binds
    @Singleton
    abstract fun bindOrchestrator(impl: OrchestratorImpl): Orchestrator
}
