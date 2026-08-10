"use client";

import { useCallback, useEffect, useRef } from "react";
import { SPEECH_COOLDOWN_MS } from "../config";
import { getActiveSpeechPriority, speak, stopSpeaking, vibrate } from "../feedback";
import type { RiskLevel } from "../risk-evaluator";
import { shouldStopSpeechOwnedByDelivery, type SpeechPriority } from "../voice-priority";
import {
  advanceRiskFeedbackFailure,
  claimRiskFeedbackForDelivery,
  completeRiskFeedback,
  createRiskFeedbackSequence,
  enqueueRiskFeedback,
  type RiskFeedbackItem
} from "../risk-feedback-sequencer";

const RISK_HAPTIC_COOLDOWN_MS = 1_500;
const RISK_LEVEL_RANK: Record<RiskLevel, number> = { none: 0, low: 1, medium: 2, high: 3 };

export type SequentialRiskDeliveryObserver = {
  onStart?: () => void;
  onEnd?: () => void;
  onFailure?: () => void;
  onCancel?: () => void;
};

export type SequentialRiskFeedbackAction = {
  key: string;
  message: string;
  riskLevel: RiskLevel;
  lastSeenAtMs: number;
  speechEnabled: boolean;
  speechPriority?: SpeechPriority;
  vibration?: VibratePattern | null;
  isCurrent: () => boolean;
  observer?: SequentialRiskDeliveryObserver;
};

type ActionPayload = Omit<SequentialRiskFeedbackAction, "key" | "riskLevel" | "lastSeenAtMs">;
type ActiveDelivery = { key: string; token: symbol; retryCount: number; timer: number | null };

function vibrationDurationMs(pattern: VibratePattern | null | undefined): number {
  if (typeof pattern === "number") return Math.max(0, pattern);
  if (!Array.isArray(pattern)) return 0;
  return pattern.reduce((total, duration) => total + Math.max(0, Number(duration) || 0), 0);
}

export function useSequentialRiskFeedback() {
  const sequenceRef = useRef(createRiskFeedbackSequence<ActionPayload>());
  const activeDeliveryRef = useRef<ActiveDelivery | null>(null);
  const mountedRef = useRef(true);
  const lastSpeechAtRef = useRef<Map<string, number>>(new Map());
  const lastSpeechRankRef = useRef<Map<string, number>>(new Map());
  const lastHapticAtRef = useRef<Map<string, number>>(new Map());
  const deliverRef = useRef<(item: RiskFeedbackItem<ActionPayload>) => void>(() => undefined);

  const claimAndDeliver = useCallback(() => {
    const claimed = claimRiskFeedbackForDelivery(sequenceRef.current, {
      nowMs: Date.now(),
      isCurrent: (item) => mountedRef.current &&
        (typeof document === "undefined" || document.visibilityState === "visible") &&
        item.payload.isCurrent()
    });
    sequenceRef.current = claimed.state;
    if (claimed.item) deliverRef.current(claimed.item);
  }, []);

  const finish = useCallback((key: string, token: symbol) => {
    if (activeDeliveryRef.current?.token !== token) return;
    if (activeDeliveryRef.current.timer !== null) window.clearTimeout(activeDeliveryRef.current.timer);
    activeDeliveryRef.current = null;
    sequenceRef.current = completeRiskFeedback(sequenceRef.current, key);
    claimAndDeliver();
  }, [claimAndDeliver]);

  const deliver = useCallback((requestedItem: RiskFeedbackItem<ActionPayload>) => {
    const item = sequenceRef.current.active?.key === requestedItem.key
      ? sequenceRef.current.active
      : null;
    if (!item || activeDeliveryRef.current?.key === item.key) return;

    const claimed = claimRiskFeedbackForDelivery(sequenceRef.current, {
      nowMs: Date.now(),
      isCurrent: (candidate) => mountedRef.current &&
        (typeof document === "undefined" || document.visibilityState === "visible") &&
        candidate.payload.isCurrent()
    });
    sequenceRef.current = claimed.state;
    if (!claimed.item) return;
    if (claimed.item.key !== item.key) {
      deliverRef.current(claimed.item);
      return;
    }

    const token = Symbol(item.key);
    const rank = item.severityRank;
    const payload = item.payload;
    const now = Date.now();
    const lastHapticAt = lastHapticAtRef.current.get(item.key) ?? 0;
    const shouldHaptic = Boolean(payload.vibration) && now - lastHapticAt >= RISK_HAPTIC_COOLDOWN_MS;
    const lastSpeechAt = lastSpeechAtRef.current.get(item.key) ?? 0;
    const lastSpeechRank = lastSpeechRankRef.current.get(item.key) ?? 0;
    const shouldSpeak = payload.speechEnabled && (rank > lastSpeechRank || now - lastSpeechAt >= SPEECH_COOLDOWN_MS);
    activeDeliveryRef.current = { key: item.key, token, retryCount: 0, timer: null };

    if (shouldHaptic) {
      lastHapticAtRef.current.set(item.key, now);
      vibrate(payload.vibration ?? null);
    }

    if (!shouldSpeak) {
      const delayMs = shouldHaptic ? vibrationDurationMs(payload.vibration) : 0;
      activeDeliveryRef.current.timer = window.setTimeout(() => finish(item.key, token), delayMs);
      return;
    }

    const attemptSpeech = () => {
      const delivery = activeDeliveryRef.current;
      if (!delivery || delivery.token !== token) return;
      const currentItem = sequenceRef.current.active?.key === item.key
        ? sequenceRef.current.active
        : item;
      const speechPayload = currentItem.payload;
      const speechRank = currentItem.severityRank;
      let failureHandled = false;
      const onFailure = () => {
        if (failureHandled || activeDeliveryRef.current?.token !== token) return;
        failureHandled = true;
        speechPayload.observer?.onFailure?.();
        const failure = advanceRiskFeedbackFailure(delivery.retryCount);
        delivery.retryCount = failure.retryCount;
        if (failure.terminal || failure.delayMs === null) {
          const failedAt = Date.now();
          lastSpeechAtRef.current.set(item.key, failedAt);
          lastSpeechRankRef.current.set(item.key, speechRank);
          finish(item.key, token);
          return;
        }
        delivery.timer = window.setTimeout(() => {
          delivery.timer = null;
          const rechecked = claimRiskFeedbackForDelivery(sequenceRef.current, {
            nowMs: Date.now(),
            isCurrent: (candidate) => candidate.key === item.key && mountedRef.current &&
              (typeof document === "undefined" || document.visibilityState === "visible") &&
              candidate.payload.isCurrent()
          });
          sequenceRef.current = rechecked.state;
          if (rechecked.item?.key === item.key) attemptSpeech();
          else {
            activeDeliveryRef.current = null;
            claimAndDeliver();
          }
        }, failure.delayMs);
      };
      const accepted = speak(speechPayload.message, speechPayload.speechPriority ?? "risk", {
        onStart: () => {
          if (activeDeliveryRef.current?.token !== token) return;
          const startedAt = Date.now();
          lastSpeechAtRef.current.set(item.key, startedAt);
          lastSpeechRankRef.current.set(item.key, speechRank);
          speechPayload.observer?.onStart?.();
        },
        onEnd: () => {
          speechPayload.observer?.onEnd?.();
          finish(item.key, token);
        },
        onFailure,
        onCancel: () => {
          if (activeDeliveryRef.current?.token !== token) return;
          speechPayload.observer?.onCancel?.();
          finish(item.key, token);
        }
      });
      if (!accepted) onFailure();
    };
    attemptSpeech();
  }, [claimAndDeliver, finish]);

  useEffect(() => {
    deliverRef.current = deliver;
  }, [deliver]);

  const enqueue = useCallback((action: SequentialRiskFeedbackAction) => {
    const rank = RISK_LEVEL_RANK[action.riskLevel];
    if (rank <= 0 || !mountedRef.current) return;
    const result = enqueueRiskFeedback(sequenceRef.current, {
      key: action.key,
      severityRank: rank,
      lastSeenAtMs: action.lastSeenAtMs,
      payload: {
        message: action.message,
        speechEnabled: action.speechEnabled,
        speechPriority: action.speechPriority,
        vibration: action.vibration,
        isCurrent: action.isCurrent,
        observer: action.observer
      }
    }, Date.now());
    sequenceRef.current = result.state;
    if (result.outcome === "preempted") {
      const previous = activeDeliveryRef.current;
      if (previous?.timer != null) window.clearTimeout(previous.timer);
      activeDeliveryRef.current = null;
      stopSpeaking();
      claimAndDeliver();
    } else if (result.outcome === "activated") {
      claimAndDeliver();
    }
  }, [claimAndDeliver]);

  const cancelActive = useCallback((keyPrefix: string) => {
    const active = sequenceRef.current.active;
    if (!active?.key.startsWith(keyPrefix)) return;
    const delivery = activeDeliveryRef.current;
    if (delivery?.timer != null) window.clearTimeout(delivery.timer);
    activeDeliveryRef.current = null;
    sequenceRef.current = completeRiskFeedback(sequenceRef.current, active.key);
    if (shouldStopSpeechOwnedByDelivery(getActiveSpeechPriority(), active.payload.speechPriority)) {
      stopSpeaking();
    }
    claimAndDeliver();
  }, [claimAndDeliver]);

  const clearForLifecycle = useCallback((notifyActiveCancellation: boolean) => {
    const active = sequenceRef.current.active;
    const ownedRiskSpeech = activeDeliveryRef.current !== null;
    if (activeDeliveryRef.current?.timer != null) window.clearTimeout(activeDeliveryRef.current.timer);
    activeDeliveryRef.current = null;
    if (notifyActiveCancellation && mountedRef.current && ownedRiskSpeech) {
      active?.payload.observer?.onCancel?.();
    }
    sequenceRef.current = createRiskFeedbackSequence<ActionPayload>();
    if (ownedRiskSpeech) stopSpeaking();
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    const stopWhenHidden = () => {
      if (document.visibilityState !== "visible") clearForLifecycle(true);
    };
    const stopOnPageHide = () => clearForLifecycle(true);
    document.addEventListener("visibilitychange", stopWhenHidden);
    window.addEventListener("pagehide", stopOnPageHide);
    return () => {
      mountedRef.current = false;
      document.removeEventListener("visibilitychange", stopWhenHidden);
      window.removeEventListener("pagehide", stopOnPageHide);
      clearForLifecycle(false);
    };
  }, [clearForLifecycle]);

  return { enqueueRiskFeedback: enqueue, cancelActiveRiskFeedback: cancelActive };
}
