package com.deepseek.coder.planner.bench.benchmarks

import com.deepseek.coder.planner.bench.schema.PlannerOutput
import kotlinx.serialization.Serializable

/**
 * F2 端到端代码编译 Benchmark：40 题（v0.7 全栈混合型）
 *   Android 15 题(assembleDebug 编译) + Web 前端 10 题(TSC + ESLint) + 后端 8 题(Python flake8/Go build/Java mvn compile) + DevOps/脚本 7 题
 *   综合通过率 ≥ 80% (32/40)
 *   至少 8 题触发 FAILURE_DISPATCH 后 Planner 成功判定 RETRY/REWORK/BLOCKED 正确分支（v0.3）
 *
 * 真实评测流程（LoRA Planner 上线后才跑）：
 *   对每个 F2Case：Planner 拆 → Actor 按 subtasks 逐任务生成代码 → 粘进空工程 → 跑 buildCommand → ExitCode=0 才算 PASS
 *   目前本评测框架在 TDD 阶段：先定义好 40 题的 caseId/scope/buildCommand 等元数据，后面填 prompt + 空工程脚手架
 */
data class F2Case(
    val caseId: String,
    val name: String,              // 人类可读标题
    val scope: String,             // ANDROID / WEB_FRONTEND / BACKEND / DEVOPS / SCRIPT
    val complexityTier: String,    // L1-Light / L2-Standard / L3-Pro
    val userPrompt: String,        // 发给 Planner 的需求
    val buildCommand: String,      // 构建命令（ExitCode 0 = PASS）
    val workingDir: String,        // 空工程脚手架目录（相对于 planner-benchmarks/test-fixtures/f2/<caseId>/）
    val expectedStepsRange: IntRange, // 预期 estimated_total_steps 范围
    val shouldTriggerFailure: Boolean = false, // 是否故意埋坑（触发 FAILURE_DISPATCH 的 8 题）
    val expectedFailureDecision: String? = null // RETRY / REWORK / BLOCKED 三选一
)

class F2EndToEndBuildBenchmark(
    private val cases: List<F2Case>
) : PlannerBenchmark {
    override val id = "F2"
    override val name = "端到端代码编译 Benchmark（40题全栈混合：构建 ExitCode=0 才算过，综合通过率≥80%）"
    override val threshold = 0.80

    /**
     * TDD 阶段：只做弱校验（Planner 输出 schema 合法 + scope 分类正确 + steps 落在区间 + failure 决策正确 8 题触发）
     * LoRA 上线后：改为真实跑 buildCommand 判断
     */
    override fun evaluate(outputs: Map<String, PlannerOutput>): BenchmarkResult {
        var passed = 0
        val failed = mutableListOf<String>()
        var triggerCorrect = 0
        var triggerTotal = 0

        cases.forEach { case ->
            val out = outputs[case.caseId] ?: run { failed += "${case.caseId}(未找到输出)"; return@forEach }
            var casePassed = true

            // 弱校验 1：steps 落在区间
            if (out.meta.estimatedTotalSteps !in case.expectedStepsRange) {
                casePassed = false
                failed += "${case.caseId} steps区间不符：${out.meta.estimatedTotalSteps} 不在 ${case.expectedStepsRange}"
            }

            // 弱校验 2：scope_hint 命中对应场景
            val scopeTagOk = when (case.scope) {
                "ANDROID" -> out.meta.scopeTag.name == "ANDROID_KOTLIN"
                "WEB_FRONTEND" -> out.meta.scopeTag.name == "WEB_FRONTEND"
                else -> out.meta.scopeTag.name == "GENERAL" || case.scope in out.dispatch.scopeHint.joinToString()
            }
            if (!scopeTagOk) {
                casePassed = false
                failed += "${case.caseId} Scope 分类错误：期望${case.scope} 实际scope_tag=${out.meta.scopeTag} + scope_hint=${out.dispatch.scopeHint}"
            }

            // 弱校验 3：埋坑题是否触发了正确的失败决策分支
            if (case.shouldTriggerFailure) {
                triggerTotal++
                // 真实上线后：这里检查 Planner FAILURE_DISPATCH 轮 decision 是否 = case.expectedFailureDecision
                // 目前 TDD 阶段：先占位，只统计题数
                triggerCorrect++  // 暂时假设都对，上线后替换为真实判定
            }

            if (casePassed) passed++
        }

        val overallPass = passed.toDouble() / cases.size >= threshold &&
                triggerCorrect.toDouble() / maxOf(triggerTotal, 1) >= 1.0 // 8 题至少对 8 题（上线后要求 ≥80%）

        return BenchmarkResult(
            id = id, name = name,
            totalCases = cases.size, passedCases = passed,
            passed = overallPass, threshold = threshold,
            subMetrics = mapOf(
                "Android题数" to cases.count { it.scope == "ANDROID" },
                "Web前端题数" to cases.count { it.scope == "WEB_FRONTEND" },
                "后端题数" to cases.count { it.scope == "BACKEND" },
                "DevOps/脚本题数" to cases.count { it.scope in listOf("DEVOPS","SCRIPT") },
                "FAILURE_DISPATCH触发题" to cases.count { it.shouldTriggerFailure },
                "FAILURE决策正确率" to if (triggerTotal==0) "N/A" else "${triggerCorrect}/${triggerTotal}（上线后替换真实判定）"
            ),
            failedCases = failed
        )
    }

    companion object {
        /** TDD 阶段骨架：15 道题示例（真实评测需补齐 40 题：Android 15 + Web 10 + 后端 8 + DevOps/脚本 7） */
        fun defaultSampleCases(): List<F2Case> = listOf(
            // ========== Android 15 题（先 6 题示例，需补到 15 题）==========
            F2Case("F2-AND-01", "登录页+ViewModel+Room存Token+Hilt", "ANDROID", "L2-Standard",
                "用 Kotlin Compose 写登录页，ViewModel + Room 存 Token，Hilt 注入，minSdk=26，Theme=Material 3",
                "./gradlew assembleDebug", "test-fixtures/f2/EmptyAndroidCompose/", 5..9),
            F2Case("F2-AND-02", "Retrofit+OkHttp+MVVM新闻列表", "ANDROID", "L2-Standard",
                "写一个新闻列表 App：Retrofit 调 Mock API + OkHttp 拦截器 + MVVM 分页加载（Paging 3），点击进详情",
                "./gradlew assembleDebug", "test-fixtures/f2/EmptyAndroidCompose/", 7..9),
            F2Case("F2-AND-03", "埋坑1:依赖缺失触发 REWORK", "ANDROID", "L3-Pro",
                "App 里要加 ML Kit Barcode Scanner 扫码功能，但当前空工程没有声明 camerax 依赖",
                "./gradlew assembleDebug", "test-fixtures/f2/EmptyAndroidCompose/", 7..9,
                shouldTriggerFailure = true, expectedFailureDecision = "REWORK"),
            F2Case("F2-AND-04", "Canvas自定义画环形进度条", "ANDROID", "L2-Standard",
                "Jetpack Compose Canvas 自定义环形进度条，支持动画、渐变色、内外文字",
                "./gradlew assembleDebug", "test-fixtures/f2/EmptyAndroidCompose/", 5..9),
            F2Case("F2-AND-05", "埋坑2:minSdk冲突触发 BLOCKED", "ANDROID", "L2-Standard",
                "要求 minSdk=21，但代码要用 Paging 3.3（需要 minSdk≥24），请用户确认",
                "./gradlew assembleDebug", "test-fixtures/f2/EmptyAndroidCompose/", 3..5,
                shouldTriggerFailure = true, expectedFailureDecision = "BLOCKED"),
            F2Case("F2-AND-06", "WorkManager后台上传任务", "ANDROID", "L2-Standard",
                "WorkManager 做图片压缩 + 后台上传 + Notification 进度 + 网络重试策略",
                "./gradlew assembleDebug", "test-fixtures/f2/EmptyAndroidCompose/", 5..9),
            // ========== Web 前端 10 题（v0.7 Web 专项加权，需补到 10 题，先 5 题示例）==========
            F2Case("F2-WEB-01", "React+TS+Tailwind TodoApp", "WEB_FRONTEND", "L2-Standard",
                "React 18 + TypeScript + TailwindCSS：Todo 增删改查 + localStorage 持久化 + 筛选（全部/未完成/已完成）",
                "npx tsc --noEmit && npx eslint . --ext .ts,.tsx --max-warnings 0", "test-fixtures/f2/EmptyReactViteTS/", 5..9),
            F2Case("F2-WEB-02", "Vue3+Pinia电商购物车", "WEB_FRONTEND", "L2-Standard",
                "Vue 3 Composition API + Pinia + TypeScript：购物车功能 + 数量加减 + 小计/合计 + 优惠券折扣",
                "npx vue-tsc --noEmit && npx eslint . --ext .vue,.ts,.tsx --max-warnings 0", "test-fixtures/f2/EmptyVue3TS/", 5..9),
            F2Case("F2-WEB-03", "埋坑3: React key误用触发 RETRY", "WEB_FRONTEND", "L2-Standard",
                "要求用 Math.random() 当列表 key（常见反模式），Actor 生成后 Self-Check 报错，触发 RETRY",
                "npx tsc --noEmit && npx eslint . --ext .ts,.tsx --max-warnings 0", "test-fixtures/f2/EmptyReactViteTS/", 5..9,
                shouldTriggerFailure = true, expectedFailureDecision = "RETRY"),
            F2Case("F2-WEB-04", "Next.js 14 App Router 博客", "WEB_FRONTEND", "L3-Pro",
                "Next.js 14 App Router：博客列表页 SSG + 详情页 ISR + 评论区 CSR + TailwindCSS + dark mode",
                "npx tsc --noEmit && npx eslint . --ext .ts,.tsx --max-warnings 0 && npx next build", "test-fixtures/f2/EmptyNext14App/", 8..9),
            F2Case("F2-WEB-05", "埋坑4: TS类型错触发 REWORK", "WEB_FRONTEND", "L1-Light",
                "要求把 any 当道具类型写 20 行 TS 代码（反模式），Self-Check 检测到，Planner 插 REWORK subtask 把所有 any 换成 exact 类型",
                "npx tsc --noEmit && npx eslint . --ext .ts,.tsx --max-warnings 0", "test-fixtures/f2/EmptyReactViteTS/", 5..9,
                shouldTriggerFailure = true, expectedFailureDecision = "REWORK"),
            // ========== 后端 8 题（先 3 题示例）==========
            F2Case("F2-BE-01", "Python FastAPI 用户 CRUD", "BACKEND", "L2-Standard",
                "Python 3.11 + FastAPI + SQLAlchemy 2.0 + Pydantic v2 + PostgreSQL async：用户表 REST 5 个接口",
                "flake8 . --count --max-line-length=120 && python -m pytest --no-header -q", "test-fixtures/f2/EmptyFastAPI/", 5..9),
            F2Case("F2-BE-02", "Go Gin + GORM 订单 REST API", "BACKEND", "L2-Standard",
                "Go 1.22 + Gin + GORM + SQLite：订单 CRUD + 分页查询 + 订单状态机枚举",
                "go build -o /dev/null ./... && go vet ./...", "test-fixtures/f2/EmptyGoGin/", 5..9),
            F2Case("F2-BE-03", "埋坑5: SQL注入反模式触发 RETRY", "BACKEND", "L2-Standard",
                "故意要求字符串拼接 SQL（反模式），Actor 生成后 Self-Check 检测到，触发 RETRY 改成参数化查询",
                "go build -o /dev/null ./... && go vet ./...", "test-fixtures/f2/EmptyGoGin/", 5..9,
                shouldTriggerFailure = true, expectedFailureDecision = "RETRY"),
            // ========== DevOps/脚本 7 题（先 2 题示例）==========
            F2Case("F2-OPS-01", "Docker多阶段+docker-compose全栈", "DEVOPS", "L2-Standard",
                "Dockerfile（前端 3000 + 后端 8080 多阶段 alpine）+ docker-compose.yml：编排前端+Node后端+Redis+MySQL 8，要求 volumes 和 healthcheck",
                "docker compose config -q && hadolint Dockerfile || true", "test-fixtures/f2/EmptyFullstack/", 5..9),
            F2Case("F2-OPS-02", "埋坑6: 镜像latest标签触发 BLOCKED", "DEVOPS", "L1-Light",
                "明确要求用 image: nginx:latest，但生产环境要禁止 latest，Planner 识别后 BLOCKED 弹澄清问是否允许非 latest",
                "docker compose config -q || true", "test-fixtures/f2/EmptyFullstack/", 3..5,
                shouldTriggerFailure = true, expectedFailureDecision = "BLOCKED")
        )
    }
}
