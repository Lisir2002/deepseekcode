package com.deepseek.coder.di

import android.content.Context
import com.deepseek.coder.data.SessionRepository
import com.deepseek.coder.data.skill.AttachedFileRepository
import com.deepseek.coder.data.skill.DocRepository
import com.deepseek.coder.data.skill.FetchDocTool
import com.deepseek.coder.data.skill.MermaidTool
import com.deepseek.coder.data.skill.ReadAttachedFileTool
import com.deepseek.coder.data.skill.SaveSnippetTool
import com.deepseek.coder.data.skill.SearchHistoryTool
import com.deepseek.coder.data.skill.SnippetRepository
import com.deepseek.coder.data.skill.ToolImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier：附加文件沙箱根目录 `filesDir/attached/`（区分多个 File 绑定）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class AttachedRootDir

/** Qualifier：代码片段库根目录 `filesDir/snippets/`（区分多个 File 绑定）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class SnippetRootDir

/**
 * Skill 系统 DI 模块。
 *
 * 用 @IntoSet 多绑定收集所有 [ToolImpl]，注入到 [com.deepseek.coder.data.skill.ToolExecutor]。
 * 新增工具只需在此 @IntoSet 注册。
 */
@Module
@InstallIn(SingletonComponent::class)
object SkillModule {

    /** 附加文件沙箱根目录 `filesDir/attached/`（SPEC §4.4）。 */
    @Provides
    @Singleton
    @AttachedRootDir
    fun provideAttachedRootDir(@ApplicationContext app: Context): File =
        File(app.filesDir, AttachedFileRepository.DIR_NAME)

    /** 代码片段库根目录 `filesDir/snippets/`（SPEC §4.2 save_snippet）。 */
    @Provides
    @Singleton
    @SnippetRootDir
    fun provideSnippetRootDir(@ApplicationContext app: Context): File =
        File(app.filesDir, SnippetRepository.DIR_NAME)

    @Provides
    @IntoSet
    @Singleton
    fun provideMermaidTool(): ToolImpl = MermaidTool()

    @Provides
    @IntoSet
    @Singleton
    fun provideReadAttachedFileTool(repo: AttachedFileRepository): ToolImpl =
        ReadAttachedFileTool(repo)

    @Provides
    @IntoSet
    @Singleton
    fun provideSaveSnippetTool(repo: SnippetRepository): ToolImpl = SaveSnippetTool(repo)

    @Provides
    @IntoSet
    @Singleton
    fun provideSearchHistoryTool(sessionRepo: SessionRepository): ToolImpl =
        SearchHistoryTool(sessionRepo)

    @Provides
    @IntoSet
    @Singleton
    fun provideFetchDocTool(repo: DocRepository): ToolImpl = FetchDocTool(repo)
}
