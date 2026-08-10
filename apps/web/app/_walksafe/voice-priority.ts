/** Pure speech arbitration and delivery-cooldown policy shared by risk, interaction, navigation, and advisory feedback. */
export type VoiceFeedbackState = "silent" | "risk_alert" | "voice_interaction" | "navigation_guidance";
export type SpeechPriority = "advisory" | "navigation" | "interaction" | "risk";
export type NavigationSpeechDelivery = { key: string; time: number };
export type NavigationSpeechAttempt = {
  delivery: NavigationSpeechDelivery;
  attempted: boolean;
  delivered: boolean;
};

const SPEECH_PRIORITY: Record<SpeechPriority, number> = {
  advisory: 0,
  navigation: 1,
  interaction: 2,
  risk: 3
};

export function shouldPreemptSpeech(
  activePriority: SpeechPriority | null,
  nextPriority: SpeechPriority
): boolean {
  return activePriority === null || SPEECH_PRIORITY[nextPriority] >= SPEECH_PRIORITY[activePriority];
}

export function shouldBlockSpeechDuringRecognition(
  recognitionActive: boolean,
  priority: SpeechPriority
): boolean {
  return recognitionActive && priority !== "risk";
}

export function shouldStopSpeechOwnedByDelivery(
  activePriority: SpeechPriority | null,
  deliveryPriority: SpeechPriority = "risk"
): boolean {
  return activePriority !== null && activePriority === deliveryPriority;
}

type VoiceFeedbackStateArgs = {
  speechEnabled: boolean;
  riskActive: boolean;
  voiceInteractionActive?: boolean;
  navigationSpeechPrompt?: string | null;
};

export function resolveVoiceFeedbackState({
  speechEnabled,
  riskActive,
  voiceInteractionActive = false,
  navigationSpeechPrompt
}: VoiceFeedbackStateArgs): VoiceFeedbackState {
  if (!speechEnabled) {
    return "silent";
  }
  if (riskActive) {
    return "risk_alert";
  }
  if (voiceInteractionActive) {
    return "voice_interaction";
  }
  if (navigationSpeechPrompt) {
    return "navigation_guidance";
  }
  return "silent";
}

export function shouldApplyNavigationStatusMessage(
  navigationStatusMessage: string | null | undefined,
  riskActive: boolean
): navigationStatusMessage is string {
  return Boolean(navigationStatusMessage) && !riskActive;
}

export function shouldAttemptNavigationSpeech(
  lastDelivery: NavigationSpeechDelivery,
  key: string,
  nowMs: number,
  cooldownMs: number
): boolean {
  return lastDelivery.key !== key || nowMs - lastDelivery.time >= cooldownMs;
}

export function commitNavigationSpeechDelivery(
  lastDelivery: NavigationSpeechDelivery,
  key: string,
  nowMs: number,
  delivered: boolean
): NavigationSpeechDelivery {
  return delivered ? { key, time: nowMs } : lastDelivery;
}

export function attemptNavigationSpeechDelivery(args: {
  lastDelivery: NavigationSpeechDelivery;
  key: string;
  nowMs: number;
  cooldownMs: number;
  deliver: () => boolean;
}): NavigationSpeechAttempt {
  if (!shouldAttemptNavigationSpeech(args.lastDelivery, args.key, args.nowMs, args.cooldownMs)) {
    return { delivery: args.lastDelivery, attempted: false, delivered: false };
  }
  const delivered = args.deliver();
  return {
    delivery: commitNavigationSpeechDelivery(args.lastDelivery, args.key, args.nowMs, delivered),
    attempted: true,
    delivered
  };
}
