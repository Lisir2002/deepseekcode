package com.deepseek.coder.di

import com.deepseek.coder.core.DefaultDispatcherProvider
import com.deepseek.coder.core.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    companion object {
        /** Application 级协程作用域，供需要长生命周期收集 flow 的 Singleton 使用（如 SkillResolver 同步缓存）。 */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Qualifier：Application 级 CoroutineScope。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
annotation class ApplicationScope
