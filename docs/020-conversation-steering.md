# 020 - Conversation Steering

## Goal

Let the orchestrator guide active conversations based on normalized conversation events while preserving service ownership boundaries.

## Current Context

- The media bridge emits normalized `conversation.*` events.
- The orchestrator owns durable call and conversation projections.
- The orchestrator already exposes `POST /v1/calls/{call_id}/conversation/commands`.
- The media bridge currently supports light commands: `interrupt`, `append_instructions`, and `request_response`.

## Proposed Scope

1. Add an orchestrator-side conversation supervisor.
   - Consume durable completed-turn events from the existing media event flow.
   - Use deterministic rules first.
   - Avoid LLM-as-judge behavior until the event and command loop is proven.

2. Emit explicit steering decisions.
   - Record why a steering action was considered.
   - Record whether a command was sent.
   - Preserve a durable audit trail of requested, applied, and failed commands.

3. Add duplicate protection.
   - Avoid applying the same steering rule repeatedly to the same turn.
   - Key decisions by call ID, turn ID, rule ID, and command type.

4. Start with simple steering rules.
   - Topic switch magic phrase: when the caller says `mimir pivot`, append an instruction to switch topics on the next answer.
   - Excessive verbosity: append an instruction to keep the next answer brief.
   - Caller confusion: request a short recap and one clarifying question.
   - Policy-sensitive phrase: append a scoped instruction for the rest of the call.

5. Keep boundaries clean.
   - The orchestrator guides policy and conversation state.
   - The media bridge applies commands to the live runtime.
   - Runtime-specific websocket and audio details stay inside the media bridge.

## Acceptance Criteria

- Completed conversation turns can trigger deterministic steering decisions.
- Steering decisions are visible in call history.
- Commands are sent through the existing media bridge command API.
- Command requested, applied, and failed events update the call projection consistently.
- The same rule does not fire repeatedly for the same turn.
- Tests cover at least one applied command, one rejected command, and one duplicate-suppressed decision.

## Notes

This is the natural feedback loop where MIMIR moves from passive call recording toward active conversation orchestration. If a NATS event spine is introduced later, this supervisor should be able to consume the same normalized event envelopes from JetStream instead of SSE.
