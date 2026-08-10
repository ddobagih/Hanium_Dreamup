import type { VoiceSttResponse } from "../lib/voice-api";
import {
  executeNavigationVoiceIntent,
  type NavigationVoiceExecutionContext,
  type VoiceActionResult
} from "../app/_walksafe/voice-intent-executor";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function response(intent: VoiceSttResponse["intent"], slots: Record<string, unknown> = {}): VoiceSttResponse {
  return { transcript: "test", normalized: "test", intent, slots };
}

function harness(overrides: Partial<NavigationVoiceExecutionContext> = {}) {
  const state = {
    destination: "서울역",
    navigationActive: true,
    voiceMessage: "",
    lastStatusMessage: "",
    spoken: [] as string[],
    vibrations: [] as Array<number | number[]>
  };
  const context: NavigationVoiceExecutionContext = {
    destination: state.destination,
    hasNavigationDestination: true,
    speechEnabled: true,
    setDestination: (value) => {
      state.destination = value;
    },
    setNavigationActive: (value) => {
      state.navigationActive = value;
    },
    setVoiceMessage: (value) => {
      state.voiceMessage = value;
    },
    setLastStatusMessage: (value) => {
      state.lastStatusMessage = value;
    },
    speak: (value) => state.spoken.push(value),
    vibrate: (value) => state.vibrations.push(value),
    ...overrides
  };
  return { context, state };
}

async function testDestinationChangeDispatchesExactPlace(): Promise<void> {
  let received = "";
  const { context, state } = harness({
    onSetDestination: (destination): VoiceActionResult => {
      received = destination;
      return { ok: true, message: `${destination} 후보 검색 완료` };
    }
  });
  const handled = await executeNavigationVoiceIntent(
    response("set_destination", { destination: "종로" }),
    context
  );
  assert(handled, "set_destination must be handled");
  assert(received === "종로", "destination callback must receive the untrimmed-place-safe slot value");
  assert(state.destination === "종로", "local destination state must change");
  assert(state.navigationActive, "a destination search must preserve active navigation until a candidate is selected");
  assert(state.voiceMessage === "종로 후보 검색 완료", "callback result must reach user feedback");
}

async function testCancelStopAndNextInstructionDispatch(): Promise<void> {
  let cancelCalls = 0;
  let stopCalls = 0;
  let nextCalls = 0;
  const { context, state } = harness({
    onCancelDestination: () => {
      cancelCalls += 1;
      return { ok: true, message: "목적지를 취소했습니다." };
    },
    onStopNavigation: () => {
      stopCalls += 1;
      return { ok: true, message: "길안내를 중지했습니다." };
    },
    onGetNextNavigationInstruction: () => {
      nextCalls += 1;
      return { ok: true, message: "20미터 앞에서 우회전하세요." };
    }
  });

  assert(await executeNavigationVoiceIntent(response("next_navigation_instruction"), context), "next query must be handled");
  assert(nextCalls === 1, "next query callback must run once");
  assert(state.lastStatusMessage === "20미터 앞에서 우회전하세요.", "next guide must become repeatable status");

  assert(await executeNavigationVoiceIntent(response("stop_navigation"), context), "stop must be handled");
  assert(stopCalls === 1 && !state.navigationActive, "stop callback must run and deactivate navigation");

  state.navigationActive = true;
  assert(await executeNavigationVoiceIntent(response("cancel_destination"), context), "cancel must be handled");
  assert(cancelCalls === 1, "cancel callback must run once");
  assert(state.destination === "" && !state.navigationActive, "successful cancel must clear destination and navigation");
}

async function testStartAndCandidateDispatchFailClosed(): Promise<void> {
  let startCalls = 0;
  let selectedIndex = 0;
  const { context, state } = harness({
    onStartNavigation: async () => {
      startCalls += 1;
      return { ok: true, message: "길안내를 시작했습니다." };
    },
    onSelectDestinationCandidateByIndex: async (index) => {
      selectedIndex = index;
      return { ok: true, message: `${index}번 목적지를 선택했습니다.` };
    }
  });
  assert(await executeNavigationVoiceIntent(response("start_navigation"), context), "start must be handled");
  assert(startCalls === 1 && state.navigationActive, "start callback result must activate navigation");

  assert(
    await executeNavigationVoiceIntent(response("select_destination_candidate", { candidate_index: 2 }), context),
    "candidate selection must be handled"
  );
  assert(selectedIndex === 2, "candidate callback must receive the requested 1-based index");
  assert(!state.navigationActive, "a successful candidate selection may replace active navigation");

  const failedSelection = harness({
    onSelectDestinationCandidateByIndex: async () => ({ ok: false, message: "후보가 없습니다." })
  });
  assert(
    await executeNavigationVoiceIntent(response("select_destination_candidate", { candidate_index: 2 }), failedSelection.context),
    "failed candidate selection must still be handled"
  );
  assert(failedSelection.state.navigationActive, "a failed candidate selection must preserve active navigation");

  const missing = harness({ destination: "", hasNavigationDestination: false, onStartNavigation: context.onStartNavigation });
  assert(await executeNavigationVoiceIntent(response("start_navigation"), missing.context), "missing destination must be handled");
  assert(missing.state.voiceMessage === "목적지를 먼저 말씀해 주세요.", "missing destination must fail closed");
  assert(startCalls === 1, "missing destination must not call the route starter");
}

async function main(): Promise<void> {
  await testDestinationChangeDispatchesExactPlace();
  await testCancelStopAndNextInstructionDispatch();
  await testStartAndCandidateDispatchFailClosed();
  assert(!(await executeNavigationVoiceIntent(response("create_report"), harness().context)), "non-navigation intents must remain unhandled");
  console.log("voice intent executor tests passed");
}

void main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
