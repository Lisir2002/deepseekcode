package com.deepseek.coder.di

import android.content.Context
import com.deepseek.coder.BuildConfig
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.remote.api.DeepSeekApi
import com.deepseek.coder.data.remote.api.FimApi
import com.deepseek.coder.data.remote.interceptors.AuthInterceptor
import com.deepseek.coder.data.remote.interceptors.Retry429Interceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"
    private const val DEEPSEEK_BETA_BASE_URL = "https://api.deepseek.com/beta/"
    private const val HTTP_CACHE_DIR = "http_cache"
    private const val HTTP_CACHE_MB = 10L * 1024 * 1024

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext app: Context): Cache? =
        runCatching { Cache(File(app.cacheDir, HTTP_CACHE_DIR), HTTP_CACHE_MB) }
            .onFailure { AppLogger.w(it, "HTTP cache unavailable") }
            .getOrNull()

    @Provides
    @Named("logging")
    fun provideLoggingInterceptor(): Interceptor =
        HttpLoggingInterceptor { message -> AppLogger.d("HTTP %s", message) }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Named("auth")
    fun provideAuthInterceptor(impl: AuthInterceptor): Interceptor = impl

    @Provides
    @Named("retry429")
    fun provideRetry429Interceptor(impl: Retry429Interceptor): Interceptor = impl

    @Provides
    @Singleton
    fun provideDeepSeekOkHttp(
        cache: Cache?,
        @Named("logging") logging: Interceptor,
        @Named("auth") auth: Interceptor,
        @Named("retry429") retry: Interceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Order matters: retry wraps around outer (applies first on return),
        // auth sets headers before logging so log shows redacted key length only.
        .addInterceptor(retry)
        .addInterceptor(auth)
        .addInterceptor(logging)
        .build()

    private val APPLICATION_JSON = "application/json".toMediaType()

    @Provides
    @Singleton
    fun provideDeepSeekRetrofit(
        json: Json,
        client: OkHttpClient
    ): Retrofit = Retrofit.Builder()
        .baseUrl(DEEPSEEK_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
        .build()

    @Provides
    @Named("beta")
    @Singleton
    fun provideDeepSeekBetaRetrofit(
        json: Json,
        client: OkHttpClient
    ): Retrofit = Retrofit.Builder()
        .baseUrl(DEEPSEEK_BETA_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
        .build()

    @Provides
    @Singleton
    fun provideDeepSeekApi(retrofit: Retrofit): DeepSeekApi = retrofit.create(DeepSeekApi::class.java)

    @Provides
    @Singleton
    fun provideFimApi(@Named("beta") betaRetrofit: Retrofit): FimApi = betaRetrofit.create(FimApi::class.java)
}
