from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Literal

ConversationCommand = Literal["interrupt", "append_instructions", "request_response"]

COMPLETED_TURN_EVENT_TYPES = {"conversation.user.turn.completed", "conversation.assistant.turn.completed"}

TOPIC_SWITCH_RULE_ID = "topic_switch_magic_word_v1"
CALLER_CONFUSION_RULE_ID = "caller_confusion_v1"
ASSISTANT_VERBOSITY_RULE_ID = "assistant_excessive_verbosity_v1"
POLICY_SENSITIVE_RULE_ID = "policy_sensitive_phrase_v1"

TOPIC_SWITCH_INSTRUCTION = (
    "The caller used the MIMIR pivot phrase. On your next turn, pivot away from the previous topic and ask what "
    "they would like to discuss next."
)
CALLER_CONFUSION_PROMPT = "Briefly recap the last answer in one or two sentences, then ask exactly one clarifying question."
ASSISTANT_VERBOSITY_INSTRUCTION = "Keep your next answer brief: lead with the main point and use no more than three short sentences."
POLICY_SENSITIVE_INSTRUCTION = (
    "For the rest of this call, keep sensitive-topic responses cautious and high level. Do not provide professional "
    "advice, and suggest qualified help when appropriate."
)

ASSISTANT_VERBOSITY_WORD_THRESHOLD = 120
ASSISTANT_VERBOSITY_CHAR_THRESHOLD = 800

_TOPIC_SWITCH_RE = re.compile(r"\bmimir\s+pivot\b", flags=re.IGNORECASE)
_CONFUSION_PHRASES = (
    "i am confused",
    "i'm confused",
    "i dont understand",
    "i don't understand",
    "what do you mean",
    "you lost me",
    "that lost me",
    "can you explain",
)
_POLICY_SENSITIVE_PHRASES = (
    "medical advice",
    "legal advice",
    "financial advice",
    "investment advice",
    "diagnose",
    "prescribe",
    "lawsuit",
    "self harm",
    "self-harm",
    "suicide",
)


@dataclass(frozen=True)
class SteeringDecision:
    call_id: str
    source_event_id: str
    source_event_type: str
    turn_id: str
    turn_index: int | None
    speaker: str
    rule_id: str
    reason: str
    command: ConversationCommand
    command_payload: dict[str, Any]

    @property
    def decision_key(self) -> str:
        return f"{self.call_id}:{self.turn_id}:{self.rule_id}:{self.command}"

    def audit_attributes(self) -> dict[str, Any]:
        return {
            "decision_key": self.decision_key,
            "rule_id": self.rule_id,
            "reason": self.reason,
            "source_event_id": self.source_event_id,
            "source_event_type": self.source_event_type,
            "turn_id": self.turn_id,
            "turn_index": self.turn_index,
            "speaker": self.speaker,
            "command": self.command,
            "command_payload": dict(self.command_payload),
        }


def evaluate_steering_decisions(event: dict[str, Any]) -> list[SteeringDecision]:
    context = _completed_turn_context(event)
    if context is None:
        return []

    text = context["text"]
    speaker = context["speaker"]
    decisions: list[SteeringDecision] = []

    if speaker == "user":
        if _TOPIC_SWITCH_RE.search(text):
            decisions.append(
                _decision(
                    context,
                    rule_id=TOPIC_SWITCH_RULE_ID,
                    reason="caller_used_mimir_pivot_magic_phrase",
                    command="append_instructions",
                    command_payload={"command": "append_instructions", "text": TOPIC_SWITCH_INSTRUCTION},
                )
            )
        if _contains_phrase(text, _CONFUSION_PHRASES):
            decisions.append(
                _decision(
                    context,
                    rule_id=CALLER_CONFUSION_RULE_ID,
                    reason="caller_expressed_confusion",
                    command="request_response",
                    command_payload={"command": "request_response", "prompt": CALLER_CONFUSION_PROMPT},
                )
            )
        if _contains_phrase(text, _POLICY_SENSITIVE_PHRASES):
            decisions.append(
                _decision(
                    context,
                    rule_id=POLICY_SENSITIVE_RULE_ID,
                    reason="caller_used_policy_sensitive_phrase",
                    command="append_instructions",
                    command_payload={"command": "append_instructions", "text": POLICY_SENSITIVE_INSTRUCTION},
                )
            )
    elif speaker == "assistant" and _is_excessively_verbose(text):
        decisions.append(
            _decision(
                context,
                rule_id=ASSISTANT_VERBOSITY_RULE_ID,
                reason="assistant_turn_exceeded_verbosity_threshold",
                command="append_instructions",
                command_payload={"command": "append_instructions", "text": ASSISTANT_VERBOSITY_INSTRUCTION},
            )
        )

    return decisions


def _completed_turn_context(event: dict[str, Any]) -> dict[str, Any] | None:
    event_type = event.get("event_type")
    if event_type not in COMPLETED_TURN_EVENT_TYPES or event.get("transient"):
        return None

    attributes = event.get("attributes") or {}
    text = str(attributes.get("text") or "").strip()
    call_id = event.get("call_id")
    source_event_id = event.get("event_id")
    turn_id = attributes.get("turn_id")
    speaker = attributes.get("speaker") or _speaker_from_event_type(str(event_type))
    turn_index = attributes.get("turn_index")

    if not call_id or not source_event_id or not turn_id or not speaker or not text:
        return None

    return {
        "call_id": str(call_id),
        "source_event_id": str(source_event_id),
        "source_event_type": str(event_type),
        "turn_id": str(turn_id),
        "turn_index": turn_index if isinstance(turn_index, int) else None,
        "speaker": str(speaker),
        "text": text,
    }


def _decision(
    context: dict[str, Any],
    *,
    rule_id: str,
    reason: str,
    command: ConversationCommand,
    command_payload: dict[str, Any],
) -> SteeringDecision:
    return SteeringDecision(
        call_id=context["call_id"],
        source_event_id=context["source_event_id"],
        source_event_type=context["source_event_type"],
        turn_id=context["turn_id"],
        turn_index=context["turn_index"],
        speaker=context["speaker"],
        rule_id=rule_id,
        reason=reason,
        command=command,
        command_payload=command_payload,
    )


def _speaker_from_event_type(event_type: str) -> str | None:
    if event_type == "conversation.user.turn.completed":
        return "user"
    if event_type == "conversation.assistant.turn.completed":
        return "assistant"
    return None


def _contains_phrase(text: str, phrases: tuple[str, ...]) -> bool:
    normalized = re.sub(r"\s+", " ", text.casefold())
    return any(phrase in normalized for phrase in phrases)


def _is_excessively_verbose(text: str) -> bool:
    words = re.findall(r"\S+", text)
    return len(words) > ASSISTANT_VERBOSITY_WORD_THRESHOLD or len(text) > ASSISTANT_VERBOSITY_CHAR_THRESHOLD
