type AssistiveAnnouncementProps = {
  enabled: boolean;
  riskActive: boolean;
  riskMessage: string;
  interactionMessage: string | null;
  navigationMessage: string | null;
};

/** Keeps one ordered screen-reader channel available when browser TTS cannot deliver speech. */
export function AssistiveAnnouncement({
  enabled,
  riskActive,
  riskMessage,
  interactionMessage,
  navigationMessage
}: AssistiveAnnouncementProps) {
  if (!enabled) return null;
  const message = riskActive ? riskMessage : interactionMessage ?? navigationMessage;
  if (!message) return null;
  return (
    <p
      className="sr-only"
      role={riskActive ? "alert" : "status"}
      aria-live={riskActive ? "assertive" : "polite"}
      aria-atomic="true"
    >
      {message}
    </p>
  );
}
