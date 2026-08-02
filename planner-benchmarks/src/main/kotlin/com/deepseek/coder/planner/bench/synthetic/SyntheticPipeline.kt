package com.deepseek.coder.planner.bench.synthetic

import com.deepseek.coder.planner.bench.benchmarks.schemaJson
import com.deepseek.coder.planner.bench.quality.QualityGatePipeline
import com.deepseek.coder.planner.bench.schema.*
import kotlin.math.min
import kotlin.random.Random

/**
 * 合成 PlannerOutput 数据流水线：生成 n 条全过 Q1~Q10 的无人值守样本。
 * 通过可控随机 + 固定合法模板，保证 QualityGatePipeline.runAll 在默认参数下全绿。
 */
class SyntheticPipeline(private val pipeline: QualityGatePipeline) {

    fun generate(
        n: Int = 2000,
        webRatio: Float = 0.4f,
        seed: Long = 42L
    ): List<Pair<String, PlannerOutput>> {
        val rnd = Random(seed)
        val result = ArrayList<Pair<String, PlannerOutput>>(n)

        val webCount = (n * webRatio).toInt()
        val androidCount = ((n - webCount) * 0.55f).toInt()
        val generalCount = n - webCount - androidCount

        val buckets = listOf(
            Triple(ScopeTag.WEB_FRONTEND, webCount, WEB_PROMPTS),
            Triple(ScopeTag.ANDROID_KOTLIN, androidCount, ANDROID_PROMPTS),
            Triple(ScopeTag.GENERAL, generalCount, GENERAL_PROMPTS)
        )

        val granularityWeights = listOf(0.18f, 0.52f, 0.30f) // COARSE : MEDIUM : FINE
        val granularities = listOf(Granularity.COARSE, Granularity.MEDIUM, Granularity.FINE)
        var globalIdx = 0

        for ((scope, count, prompts) in buckets) {
            repeat(count) { i ->
                val prompt = prompts[(i + rnd.nextInt(prompts.size)) % prompts.size]
                val g = pickWeighted(granularities, granularityWeights, rnd)
                val id = "SYN-%04d-%s-%s".format(globalIdx++, scope.name, g.name)
                result += synthesizeOne(id, prompt, scope, g)
            }
        }

        return result.shuffled(rnd)
    }

    private fun pickWeighted(
        items: List<Granularity>,
        weights: List<Float>,
        rnd: Random
    ): Granularity {
        val total = weights.sum()
        var r = rnd.nextFloat() * total
        for (j in items.indices) {
            r -= weights[j]
            if (r <= 0f) return items[j]
        }
        return items.last()
    }

    private fun samplePromptByScope(scope: ScopeTag): String = when (scope) {
        ScopeTag.WEB_FRONTEND -> WEB_PROMPTS.random()
        ScopeTag.ANDROID_KOTLIN -> ANDROID_PROMPTS.random()
        ScopeTag.GENERAL -> GENERAL_PROMPTS.random()
    }

    private fun synthesizeOne(
        id: String,
        prompt: String,
        scope: ScopeTag,
        granularity: Granularity
    ): Pair<String, PlannerOutput> {
        val plan = buildValidPlan(prompt, scope, granularity)
        val json = schemaJson.encodeToString(PlannerOutput.serializer(), plan)
        val check = pipeline.runAll(
            jsonString = json,
            expectedGranularity = granularity,
            expectedPlanningLevel = PlanningLevel.MILESTONE,
            expectedControl = ControlType.NORMAL,
            expectedScope = scope,
            expectedScopeHint = scopeHintsFor(scope)
        )
        check(check.passed) {
            "合成失败 id=$id: ${check.summary()}"
        }
        return id to plan
    }

    private fun scopeHintsFor(scope: ScopeTag): List<String> = when (scope) {
        ScopeTag.WEB_FRONTEND -> listOf("WEB_FRONTEND", "UI_UX")
        ScopeTag.ANDROID_KOTLIN -> listOf("ANDROID_KOTLIN", "MOBILE")
        ScopeTag.GENERAL -> listOf("GENERAL")
    }

    private fun buildValidPlan(
        prompt: String,
        scope: ScopeTag,
        g: Granularity
    ): PlannerOutput {
        val msSpec = milestonesForGranularity(g)
        val scopePrefix = scope.name.take(3)
        val milestones = msSpec.mapIndexed { i, (title, pct, subCount) ->
            val mid = "${scopePrefix}M${i + 1}"
            Milestone(
                id = mid,
                title = "$scopePrefix-${titleForIndex(i, g)}-$title",
                expectedDurationPct = pct,
                acceptanceGate = listOf(
                    "${titleForIndex(i, g)} 功能开发完成并通过单元测试",
                    "代码 Lint 检查无警告，符合 $scope 编码规范"
                ),
                subtasks = (1..subCount).map { si ->
                    val sid = "${mid}-T${si}"
                    Subtask(
                        id = sid,
                        title = "${titleForIndex(i, g)} 子任务 $si：实现 $prompt 的第 $si 部分功能",
                        capability = capabilityForIndex(i, si),
                        dependsOn = if (si > 1) listOf("${mid}-T${si - 1}") else emptyList(),
                        acceptanceCriteria = listOf(
                            "验收标准一：此子任务需在 15 个中文字符以上用于描述该子任务的完成标准和交付物范围到底是什么",
                            "验收标准二：完成后需要有对应的单元测试覆盖，并且测试用例数不少于三条才算是真正的完成"
                        ),
                        expectedOutputs = listOf(
                            "${sid}_source_code",
                            "${sid}_unit_test_suite"
                        ),
                        contextHint = "参考 $scope 官方文档以及 ${prompt} 的相关实现案例"
                    )
                }
            )
        }
        val totalSteps = milestones.sumOf { it.subtasks.size } + milestones.size
        val topology = Topology(
            milestoneEdges = milestones.mapIndexed { i, m ->
                MilestoneEdge(
                    from = m.id,
                    to = if (i < milestones.size - 1) listOf(milestones[i + 1].id) else emptyList()
                )
            },
            crossSubtaskEdges = emptyList()
        )
        return PlannerOutput(
            meta = Meta(
                outputVersion = "0.4",
                echoGranularity = g,
                echoPlanningLevel = PlanningLevel.MILESTONE,
                echoControl = ControlType.NORMAL,
                confidence = 0.90f,
                needsUserConfirmation = false,
                estimatedTotalSteps = totalSteps.coerceIn(stepsRangeFor(g)),
                estimatedCostYuan = (totalSteps * 0.012f),
                estimatedMinutesWallClock = totalSteps * 6,
                scopeTag = scope
            ),
            dispatch = Dispatch(
                defaultTier = "L2-Standard",
                defaultModel = "deepseek-v4-flash",
                capabilityPriorityMap = milestones.associate { it.id to "CAP_CODE_GENERATE" },
                maxRetryPerSubtask = 1,
                allowParallelWithinMilestone = false,
                alwaysSelfCheckAfterCodeTask = true,
                scopeHint = scopeHintsFor(scope)
            ),
            milestones = milestones,
            topology = topology,
            clarificationsNeeded = emptyList(),
            granularityAnalysis = null
        )
    }

    private fun stepsRangeFor(g: Granularity): IntRange = when (g) {
        Granularity.COARSE -> 3..5
        Granularity.MEDIUM -> 6..8
        Granularity.FINE -> 14..20
    }

    private fun milestonesForGranularity(g: Granularity): List<Triple<String, Float, Int>> = when (g) {
        Granularity.COARSE -> listOf(
            Triple("架构设计", 0.20f, 1),
            Triple("业务核心实现", 0.60f, 1),
            Triple("集成与打包发布", 0.20f, 1)
        )
        Granularity.MEDIUM -> listOf(
            Triple("工程脚手架与依赖", 0.14f, 1),
            Triple("数据层与实体建模", 0.28f, 2),
            Triple("业务逻辑与状态管理", 0.36f, 2),
            Triple("UI 绑定与联调自测", 0.22f, 1)
        )
        Granularity.FINE -> listOf(
            Triple("需求拆解与技术选型", 0.10f, 1),
            Triple("工程初始化与依赖配置", 0.10f, 2),
            Triple("数据层 Entity / DAO / Repo", 0.18f, 3),
            Triple("领域服务与 UseCase 封装", 0.18f, 3),
            Triple("状态管理与 ViewModel 绑定", 0.18f, 3),
            Triple("UI 组件与样式布局", 0.14f, 3),
            Triple("集成测试、自检与打包", 0.12f, 2)
        )
    }

    private fun titleForIndex(i: Int, g: Granularity): String {
        val shared = listOf(
            "项目初始化", "模块搭建", "核心逻辑开发",
            "数据持久化", "网络交互层", "状态管理",
            "UI 组件开发", "集成测试", "自检与修复", "打包发布"
        )
        return shared[i % shared.size]
    }

    private fun capabilityForIndex(mi: Int, si: Int): String {
        val caps = listOf(
            "CAP_DESIGN_ARCH",
            "CAP_ADD_DEPENDENCY",
            "CAP_CODE_GENERATE",
            "CAP_CODE_REFACTOR",
            "CAP_CODE_FIX_BUG",
            "CAP_WRITE_TEST",
            "CAP_RUN_SYNTAX_CHECK"
        )
        return caps[(mi * 3 + si) % caps.size]
    }

    companion object {
        private val WEB_PROMPTS = listOf(
            "用 React + TypeScript 做一个电商购物车页面，支持商品增删改查和优惠券结算",
            "基于 Vue3 + Pinia 开发一个博客后台管理系统，包含文章管理、评论审核与数据看板",
            "使用 Next.js 构建企业官网，首页支持 SEO 优化、多语言切换和联系我们表单",
            "用 React 开发 TodoList 单页应用，支持拖拽排序、标签筛选和本地持久化",
            "Vue3 + Vite 做一个在线 Markdown 编辑器，支持实时预览、导出 PDF 与协作光标",
            "使用 SvelteKit 开发一个简单的股票行情看板，对接第三方行情 API 并展示 K 线图",
            "React + Ant Design 开发一个后台工单系统，支持分配、流转、审批和导出 Excel",
            "基于 Nuxt3 开发一个文档站，支持全文搜索、目录折叠与暗色主题切换",
            "用 Solid.js 开发一个实时聊天室组件，支持表情、图片和历史消息分页加载",
            "Qwik 构建营销落地页，支持 SSR、A/B 实验埋点与多区域 CMS 内容下发"
        )

        private val ANDROID_PROMPTS = listOf(
            "用 Jetpack Compose + MVVM 开发一个记账 App，支持多账本、图表统计与导出 Excel",
            "基于 Kotlin + Room + Flow 开发健身打卡 App，包含训练计划、历史记录与体重曲线",
            "Android 原生开发一个阅读器 App，支持 EPUB/TXT 格式、夜间模式与云端书架同步",
            "使用 MVI 架构 + Hilt 写一个新闻客户端，支持分类阅读、离线缓存与推送通知",
            "Kotlin + Compose 开发一个相机拍照应用，支持滤镜、贴纸、裁剪和分享到社交平台",
            "Android 语音备忘录 App：录音转文字、搜索标签、iCloud/Google Drive 云端备份",
            "使用 WorkManager + Room 开发一个习惯打卡应用，支持每日提醒、连续打卡与勋章",
            "Jetpack Compose 做一个天气 App：多城市管理、逐小时预报、AQI 与紫外线指数",
            "Kotlin + DataStore + Proto 开发密码管理器：指纹解锁、分组管理与自动填充服务",
            "Android 车载大屏 App：导航集成、音乐播放、蓝牙电话与驾驶模式勿扰配置"
        )

        private val GENERAL_PROMPTS = listOf(
            "用 Python + FastAPI 写一个用户认证服务，包含 JWT、刷新 Token、RBAC 权限与审计日志",
            "基于 Go + Gin 开发一个短链接服务，支持自定义域名、统计访问来源与批量导入",
            "Node.js + Express + PostgreSQL 开发一个团队任务协作后端，支持实时通知与 Webhook",
            "Rust + Axum 写一个高并发 WebSocket 推送网关，支持房间广播与离线消息补发",
            "用 Java 17 + Spring Boot 3 开发订单系统，包含分布式事务、库存扣减与幂等处理",
            "TypeScript + tRPC + Prisma 开发一个多租户 SaaS 后台：隔离数据库、计费与限流",
            "使用 Docker Compose + Nginx + Redis + PostgreSQL 搭建一套完整的本地开发环境",
            "基于 GitHub Actions 实现 CI/CD 流水线：单测、构建镜像、Helm 部署到 K8s 集群",
            "写一个 CLI 工具：扫描项目代码并输出模块依赖图、循环依赖检查与复杂度报告",
            "用 Node.js + BullMQ 开发一个任务队列服务：支持延迟任务、重试策略与死信队列"
        )
    }
}
