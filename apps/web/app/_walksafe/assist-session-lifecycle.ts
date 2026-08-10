export type AssistSessionLifecycle = "active" | "paused" | "stopped";
export type AssistSessionLifecycleEvent = "background" | "foreground" | "user_resume" | "user_stop";

export function transitionAssistSessionLifecycle(
  current: AssistSessionLifecycle,
  event: AssistSessionLifecycleEvent
): AssistSessionLifecycle {
  if (event === "user_stop") return "stopped";
  if (event === "user_resume") return "active";
  if (event === "background") return current === "active" ? "paused" : current;
  // Foregrounding alone never reopens a camera, microphone or location watch.
  return current;
}
