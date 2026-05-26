export type VoiceFeedbackState = "silent" | "risk_alert" | "navigation_guidance";

type VoiceFeedbackStateArgs = {
  speechEnabled: boolean;
  riskActive: boolean;
  navigationSpeechPrompt?: string | null;
};

export function resolveVoiceFeedbackState({
  speechEnabled,
  riskActive,
  navigationSpeechPrompt
}: VoiceFeedbackStateArgs): VoiceFeedbackState {
  if (!speechEnabled) {
    return "silent";
  }
  if (riskActive) {
    return "risk_alert";
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
