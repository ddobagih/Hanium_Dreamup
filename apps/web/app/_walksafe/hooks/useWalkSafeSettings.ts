"use client";

import { useCallback, useMemo, useState } from "react";

export type EmergencyContact = {
  id: string;
  name: string;
  phone: string;
};

export type WalkSafeSettings = {
  schemaVersion: 3;
  guardianName: string;
  guardianPhone: string;
  emergencyContacts: EmergencyContact[];
  privacyConsentVersion: string | null;
};

type WalkSafeSettingsForm = {
  emergencyContacts: EmergencyContact[];
};

const STORAGE_KEY = "walksafe.settings.v1";
const CURRENT_SCHEMA_VERSION = 3;
const CURRENT_PRIVACY_CONSENT_VERSION = "2026-05-26-local-only";
export const MAX_EMERGENCY_CONTACTS = 3;

export function normalizePhone(value: string): string {
  return value.replace(/[^0-9+\-\s()]/g, "").slice(0, 32);
}

export function isValidGuardianPhone(value: string): boolean {
  const digits = value.replace(/\D/g, "");
  return digits.length === 0 || (digits.length >= 9 && digits.length <= 12);
}

export function maskGuardianPhone(value: string): string {
  const digits = value.replace(/\D/g, "");
  if (digits.length < 7) {
    return value;
  }
  return `${digits.slice(0, 3)}-****-${digits.slice(-4)}`;
}

function contactId(index: number): string {
  return `contact-${index + 1}`;
}

function emptyContact(index: number): EmergencyContact {
  return { id: contactId(index), name: "", phone: "" };
}

function normalizeContact(value: Partial<EmergencyContact> | null | undefined, index: number): EmergencyContact {
  return {
    id: typeof value?.id === "string" && value.id ? value.id.slice(0, 40) : contactId(index),
    name: typeof value?.name === "string" ? value.name.slice(0, 40) : "",
    phone: typeof value?.phone === "string" ? normalizePhone(value.phone) : ""
  };
}

export function sanitizeEmergencyContacts(values: Partial<EmergencyContact>[]): EmergencyContact[] {
  const contacts = values.slice(0, MAX_EMERGENCY_CONTACTS).map((value, index) => normalizeContact(value, index));
  return contacts.length > 0 ? contacts : [emptyContact(0)];
}

function savedContacts(values: EmergencyContact[]): EmergencyContact[] {
  return sanitizeEmergencyContacts(values)
    .map((contact, index) => normalizeContact({ ...contact, id: contactId(index) }, index))
    .filter((contact) => contact.name.trim() || contact.phone.trim());
}

function safeGetStoredSettings(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function safeSetStoredSettings(settings: WalkSafeSettings): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    return true;
  } catch {
    return false;
  }
}

function safeRemoveStoredSettings(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  try {
    window.localStorage.removeItem(STORAGE_KEY);
    return true;
  } catch {
    return false;
  }
}

export function parseStoredSettings(value: string | null): WalkSafeSettings | null {
  if (!value) {
    return null;
  }

  try {
    const parsed = JSON.parse(value) as Partial<WalkSafeSettings> & { version?: number };
    const migratedContacts = Array.isArray(parsed.emergencyContacts)
      ? sanitizeEmergencyContacts(parsed.emergencyContacts)
      : sanitizeEmergencyContacts([
          {
            id: "contact-1",
            name: typeof parsed.guardianName === "string" ? parsed.guardianName : "",
            phone: typeof parsed.guardianPhone === "string" ? parsed.guardianPhone : ""
          }
        ]);
    const persistedContacts = savedContacts(migratedContacts);
    const primary = persistedContacts[0] ?? emptyContact(0);
    return {
      schemaVersion: CURRENT_SCHEMA_VERSION,
      guardianName: primary.name,
      guardianPhone: primary.phone,
      emergencyContacts: persistedContacts,
      privacyConsentVersion:
        typeof parsed.privacyConsentVersion === "string" ? parsed.privacyConsentVersion : null
    };
  } catch {
    return null;
  }
}

function defaultSettings(): WalkSafeSettings {
  return {
    schemaVersion: CURRENT_SCHEMA_VERSION,
    guardianName: "",
    guardianPhone: "",
    emergencyContacts: [],
    privacyConsentVersion: null
  };
}

function initialSettings(): WalkSafeSettings {
  if (typeof window === "undefined") {
    return defaultSettings();
  }
  return parseStoredSettings(safeGetStoredSettings()) ?? defaultSettings();
}

function formFromSettings(settings: WalkSafeSettings): WalkSafeSettingsForm {
  return {
    emergencyContacts: settings.emergencyContacts.length > 0 ? sanitizeEmergencyContacts(settings.emergencyContacts) : [emptyContact(0)]
  };
}

function settingsFromContacts(contacts: EmergencyContact[], privacyConsentVersion: string | null): WalkSafeSettings {
  const persistedContacts = savedContacts(contacts);
  const primary = persistedContacts[0] ?? emptyContact(0);
  return {
    schemaVersion: CURRENT_SCHEMA_VERSION,
    guardianName: primary.name,
    guardianPhone: primary.phone,
    emergencyContacts: persistedContacts,
    privacyConsentVersion
  };
}

export function useWalkSafeSettings() {
  const initial = useMemo(() => initialSettings(), []);
  const [settings, setSettings] = useState<WalkSafeSettings>(() => initial);
  const [form, setForm] = useState<WalkSafeSettingsForm>(() => formFromSettings(initial));
  const [settingsMessage, setSettingsMessage] = useState("긴급 연락처는 이 기기에만 저장되며 전화/SMS는 발신하지 않습니다.");
  const [settingsExpanded, setSettingsExpanded] = useState(false);

  const updateEmergencyContactName = useCallback((contactIdValue: string, value: string) => {
    setForm((current) => ({
      emergencyContacts: current.emergencyContacts.map((contact) =>
        contact.id === contactIdValue ? { ...contact, name: value.slice(0, 40) } : contact
      )
    }));
  }, []);

  const updateEmergencyContactPhone = useCallback((contactIdValue: string, value: string) => {
    setForm((current) => ({
      emergencyContacts: current.emergencyContacts.map((contact) =>
        contact.id === contactIdValue ? { ...contact, phone: normalizePhone(value) } : contact
      )
    }));
  }, []);

  const updateGuardianName = useCallback((value: string) => {
    setForm((current) => {
      const contacts = sanitizeEmergencyContacts(current.emergencyContacts);
      contacts[0] = { ...contacts[0], name: value.slice(0, 40) };
      return { emergencyContacts: contacts };
    });
  }, []);

  const updateGuardianPhone = useCallback((value: string) => {
    setForm((current) => {
      const contacts = sanitizeEmergencyContacts(current.emergencyContacts);
      contacts[0] = { ...contacts[0], phone: normalizePhone(value) };
      return { emergencyContacts: contacts };
    });
  }, []);

  const addEmergencyContact = useCallback(() => {
    setForm((current) => {
      if (current.emergencyContacts.length >= MAX_EMERGENCY_CONTACTS) {
        return current;
      }
      return {
        emergencyContacts: [...current.emergencyContacts, emptyContact(current.emergencyContacts.length)]
      };
    });
  }, []);

  const removeEmergencyContact = useCallback((contactIdValue: string) => {
    setForm((current) => ({
      emergencyContacts: sanitizeEmergencyContacts(
        current.emergencyContacts
          .filter((contact) => contact.id !== contactIdValue)
          .map((contact, index) => ({ ...contact, id: contactId(index) }))
      )
    }));
  }, []);

  const saveSettings = useCallback(() => {
    const contacts = sanitizeEmergencyContacts(form.emergencyContacts);
    if (contacts.some((contact) => !isValidGuardianPhone(contact.phone))) {
      setSettingsMessage("전화번호 형식을 확인해 주세요. 연락처는 이 기기에만 저장됩니다.");
      return;
    }
    const nextSettings = settingsFromContacts(contacts, CURRENT_PRIVACY_CONSENT_VERSION);
    const stored = safeSetStoredSettings(nextSettings);
    setSettings(nextSettings);
    setForm(formFromSettings(nextSettings));
    setSettingsMessage(
      stored
        ? `저장 완료 · 긴급 연락처 ${nextSettings.emergencyContacts.length}개는 표시용이며 전화/SMS는 발신하지 않습니다.`
        : "브라우저 저장소를 사용할 수 없습니다. 연락처는 현재 화면에만 유지됩니다."
    );
  }, [form.emergencyContacts]);

  const clearSettings = useCallback(() => {
    safeRemoveStoredSettings();
    const nextSettings = defaultSettings();
    setSettings(nextSettings);
    setForm(formFromSettings(nextSettings));
    setSettingsMessage("긴급 연락처를 이 기기에서 지웠습니다. 전화/SMS는 발신하지 않습니다.");
  }, []);

  const guardianSummary = useMemo(() => {
    if (settings.emergencyContacts.length === 0) {
      return "긴급 연락처 미설정";
    }
    const first = settings.emergencyContacts[0];
    const extra = settings.emergencyContacts.length > 1 ? ` 외 ${settings.emergencyContacts.length - 1}명` : "";
    return [first.name, first.phone ? maskGuardianPhone(first.phone) : ""].filter(Boolean).join(" · ") + extra;
  }, [settings.emergencyContacts]);

  const setupChecklist = useMemo(
    () => [
      { id: "camera", label: "카메라 권한 확인", done: false },
      { id: "location", label: "위치 권한 확인", done: false },
      { id: "voice", label: "음성 안내/명령 확인", done: false },
      { id: "emergency", label: "긴급 연락처 local-only 확인", done: settings.emergencyContacts.length > 0 },
      { id: "privacy", label: "개인정보 local-only 안내 확인", done: Boolean(settings.privacyConsentVersion) }
    ],
    [settings.emergencyContacts.length, settings.privacyConsentVersion]
  );

  const toggleSettingsExpanded = useCallback(() => {
    setSettingsExpanded((current) => !current);
  }, []);

  return {
    settings,
    settingsForm: form,
    settingsMessage,
    settingsExpanded,
    guardianSummary,
    setupChecklist,
    updateGuardianName,
    updateGuardianPhone,
    updateEmergencyContactName,
    updateEmergencyContactPhone,
    addEmergencyContact,
    removeEmergencyContact,
    saveSettings,
    clearSettings,
    toggleSettingsExpanded
  };
}
