package com.deepseek.coder.domain.workflow

import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatStreamEvent
import com.deepseek.coder.domain.models.UsageSnapshot

/**
 * Orchestrator FSM states.
 *
 * Transitions (high level):
 *   IDLE → CLASSIFY → (GOVERN_CONTEXT → DECOMPOSE → EXECUTE → SELF_CHECK → …) → DONE | FAILURE
 *                        ↓
 *                   CLARIFY_QUESTION (wait user input) → CLASSIFY again
 */
enum class WorkflowState {
    IDLE,
    CLASSIFY,
    CLARIFY_QUESTION,
    GOVERN_CONTEXT,
    DECOMPOSE,
    EXECUTE,
    SELF_CHECK,
    RETRY_FIX,
    DONE,
    FAILURE
}

/**
 * Events emitted from the [Orchestrator] upstream to the UI / ChatViewModel.
 *
 * Unlike [ChatStreamEvent] (which carries *assistant content deltas*),
 * OrchestratorEvent carries *workflow control* information: which node is
 * running, the produced plan, clarification questions, self-check issues.
 */
sealed class OrchestratorEvent {
    /** A new workflow started. */
    data class Started(val runId: String) : OrchestratorEvent()

    /** FSM state transition. */
    data class StateTransition(val from: WorkflowState, val to: WorkflowState) : OrchestratorEvent()

    /** Classification result. */
    data class Classification(val value: IntentClassification) : OrchestratorEvent()

    /** Orchestrator is asking the user clarifying questions; the run suspends until a CLARIFY_ANSWER is fed back in. */
    data class ClarifyQuestion(val questions: List<String>) : OrchestratorEvent()

    /** Decomposed plan. */
    data class PlanProduced(val plan: WorkflowPlan) : OrchestratorEvent()

    /** Step-level progress (started / text delta / finished). */
    data class StepStarted(val step: WorkflowStep) : OrchestratorEvent()
    data class StepFinished(val step: WorkflowStep) : OrchestratorEvent()

    /** Self-check verdict.  If pass=false the Orchestrator may transition into RETRY_FIX. */
    data class SelfCheck(val result: SelfCheckResult) : OrchestratorEvent()

    /** Context governor report (how many tokens were trimmed / summarised). */
    data class ContextTrimmed(
        val originalCount: Int,
        val finalCount: Int,
        val summarisedOldMessages: Boolean
    ) : OrchestratorEvent()

    /** Run completed; finalAssistant contains the merged answer to render to the user. */
    data class Completed(
        val finalAssistant: ChatMessage,
        val usage: UsageSnapshot?,
        val retryCount: Int
    ) : OrchestratorEvent()

    /** Run failed; [ChatStreamEvent.Failure] is also emitted by the Chat side so error handling is unified. */
    data class Failed(val error: Throwable) : OrchestratorEvent()
}

/**
 * The public surface of the workflow Orchestrator.
 *
 * Implementations live in the `data/workflow` layer; UI (ChatViewModel)
 * only depends on this interface to keep Clean-architecture boundaries clean.
 */
interface Orchestrator {

    /**
     * Run a full workflow for the given [userMessage] appended to [history].
     *
     * The returned Flow emits both upstream [OrchestratorEvent]s for workflow UI
     * and downstream [ChatStreamEvent]s (TextDelta / ReasoningDelta / Finish etc.)
     * so the existing ChatViewModel streaming consumer can be reused with minimal changes.
     */
    fun run(
        runId: String,
        history: List<ChatMessage>,
        userMessage: ChatMessage
    ): kotlinx.coroutines.flow.Flow<WorkflowEvent>

    /**
     * Answer a currently pending CLARIFY_QUESTION for [runId].
     * The Orchestrator resumes the run; the caller should re-subscribe to [run]'s Flow
     * or (implementation detail) the Flow continues transparently.
     */
    suspend fun answerClarification(runId: String, answers: List<String>)
}

/**
 * Union event type produced by the Orchestrator Flow.  One carrier type is
 * simpler for collectors than handling two parallel Flows, and avoids any
 * race between orchestration progress and chat content deltas.
 */
sealed class WorkflowEvent {
    data class Orch(val event: OrchestratorEvent) : WorkflowEvent()
    data class Chat(val event: ChatStreamEvent) : WorkflowEvent()
}
