import type { VoiceSttResponse } from "@/lib/voice-api";
import { destinationFromSlots } from "./utils";

export type VoiceActionResult = { ok: boolean; message: string };

export type NavigationVoiceExecutionContext = {
  destination: string;
  hasNavigationDestination: boolean;
  speechEnabled: boolean;
  setDestination: (destination: string) => void;
  setNavigationActive: (active: boolean) => void;
  setVoiceMessage: (message: string) => void;
  setLastStatusMessage: (message: string) => void;
  onSetDestination?: (destination: string) => void | VoiceActionResult | Promise<void | VoiceActionResult>;
  onCancelDestination?: () => VoiceActionResult;
  onSelectDestinationCandidateByIndex?: (candidateIndex: number) => VoiceActionResult | Promise<VoiceActionResult>;
  onStartNavigation?: () => Promise<VoiceActionResult>;
  onStopNavigation?: () => void | VoiceActionResult;
  onGetNextNavigationInstruction?: () => VoiceActionResult;
  speak: (message: string) => void;
  vibrate: (pattern: number | number[]) => void;
};

function candidateIndexFromSlots(slots: Record<string, unknown>): number | null {
  const value = slots.candidate_index ?? slots.candidateIndex ?? slots.index;
  if (typeof value === "number" && Number.isInteger(value) && value >= 1) return value;
  if (typeof value === "string" && /^[1-9]\d*$/.test(value.trim())) return Number(value);
  return null;
}

function respond(context: NavigationVoiceExecutionContext, result: VoiceActionResult): void {
  context.setVoiceMessage(result.message);
  context.vibrate(result.ok ? 70 : [120, 80, 120]);
  if (context.speechEnabled) context.speak(result.message);
}

/** Executes accepted navigation intents against injected callbacks so action dispatch is unit-testable. */
export async function executeNavigationVoiceIntent(
  result: VoiceSttResponse,
  context: NavigationVoiceExecutionContext
): Promise<boolean> {
  const intent = result.intent;

  if (intent === "set_destination") {
    const destination = destinationFromSlots(result.slots);
    if (destination) {
      context.setDestination(destination);
      const destinationResult = await context.onSetDestination?.(destination);
      if (destinationResult) {
        respond(context, destinationResult);
        return true;
      }
    }
    const message = destination ? `${destination} 목적지 저장` : "목적지를 다시 말씀해 주세요.";
    context.setVoiceMessage(message);
    context.vibrate(70);
    if (context.speechEnabled) {
      context.speak(destination ? `${destination} 목적지를 저장했습니다.` : message);
    }
    return true;
  }

  if (intent === "select_destination_candidate") {
    const candidateIndex = candidateIndexFromSlots(result.slots);
    if (candidateIndex === null) {
      respond(context, { ok: false, message: "몇 번째 목적지를 선택할지 다시 말씀해 주세요." });
      return true;
    }
    if (!context.onSelectDestinationCandidateByIndex) {
      respond(context, { ok: false, message: "선택할 목적지 후보가 없습니다. 목적지를 먼저 말씀해 주세요." });
      return true;
    }
    const selectionResult = await context.onSelectDestinationCandidateByIndex(candidateIndex);
    if (selectionResult.ok) context.setNavigationActive(false);
    respond(context, selectionResult);
    return true;
  }

  if (intent === "cancel_destination") {
    const cancelResult = context.onCancelDestination?.() ?? { ok: false, message: "취소할 목적지가 없습니다." };
    if (cancelResult.ok) {
      context.setDestination("");
      context.setNavigationActive(false);
    }
    context.setVoiceMessage(cancelResult.message);
    context.vibrate(cancelResult.ok ? [80, 50, 80] : [120, 80, 120]);
    if (context.speechEnabled) context.speak(cancelResult.message);
    return true;
  }

  if (intent === "stop_navigation") {
    const stopResult = context.onStopNavigation?.();
    const actionResult = stopResult ?? { ok: true, message: "길안내를 중지했습니다." };
    if (actionResult.ok) context.setNavigationActive(false);
    context.setVoiceMessage(actionResult.message);
    context.vibrate(actionResult.ok ? [80, 50, 80] : [120, 80, 120]);
    if (context.speechEnabled) context.speak(actionResult.message);
    return true;
  }

  if (intent === "next_navigation_instruction") {
    const actionResult = context.onGetNextNavigationInstruction?.() ?? {
      ok: false,
      message: "진행 중인 길안내가 없습니다."
    };
    context.setVoiceMessage(actionResult.message);
    context.setLastStatusMessage(actionResult.message);
    context.vibrate(actionResult.ok ? 70 : [120, 80, 120]);
    if (context.speechEnabled) context.speak(actionResult.message);
    return true;
  }

  if (intent === "start_navigation" || intent === "reroute_navigation") {
    if (!context.destination && !context.hasNavigationDestination) {
      respond(context, { ok: false, message: "목적지를 먼저 말씀해 주세요." });
      return true;
    }
    if (context.onStartNavigation) {
      const actionResult = await context.onStartNavigation();
      context.setNavigationActive(actionResult.ok);
      context.setVoiceMessage(actionResult.message);
      context.vibrate(actionResult.ok ? [70, 50, 120] : [120, 80, 120]);
      if (context.speechEnabled && !actionResult.ok) context.speak(actionResult.message);
      return true;
    }
    context.setNavigationActive(true);
    context.setVoiceMessage(intent === "reroute_navigation" ? "재탐색 준비" : `${context.destination} 안내 시작 준비`);
    context.vibrate([70, 50, 120]);
    if (context.speechEnabled) {
      context.speak(intent === "reroute_navigation" ? "경로를 다시 확인합니다." : "길 안내를 시작합니다.");
    }
    return true;
  }

  return false;
}
