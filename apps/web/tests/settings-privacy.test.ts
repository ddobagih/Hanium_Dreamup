import {
  isValidGuardianPhone,
  maskGuardianPhone,
  parseStoredSettings,
  sanitizeEmergencyContacts
} from "../app/_walksafe/hooks/useWalkSafeSettings";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function testSettingsMigrationAndMasking() {
  const parsed = parseStoredSettings(JSON.stringify({ guardianName: "보호자", guardianPhone: "010-1234-5678" }));

  assert(parsed?.schemaVersion === 3, "stored v0/v1 settings should migrate to schema v3");
  assert(parsed.guardianName === "보호자", "guardian name should be preserved");
  assert(parsed.emergencyContacts.length === 1, "legacy guardian should become primary emergency contact");
  assert(maskGuardianPhone(parsed.guardianPhone) === "010-****-5678", "guardian phone should be masked");
}

function testMultipleContactsAreLocalOnlyShape() {
  const parsed = parseStoredSettings(
    JSON.stringify({
      emergencyContacts: [
        { id: "a", name: "주보호자", phone: "010-1111-2222" },
        { id: "b", name: "보조", phone: "02-123-4567" },
        { id: "c", name: "친구", phone: "010-3333-4444" },
        { id: "d", name: "초과", phone: "010-9999-9999" }
      ]
    })
  );

  assert(parsed !== null, "multiple emergency contacts should parse");
  assert(parsed.emergencyContacts.length === 3, "contacts should be capped to three local-only entries");
  assert(parsed.guardianName === "주보호자", "primary guardian should be first contact");
}

function testPhoneValidationAllowsEmptyButRejectsTooShort() {
  assert(isValidGuardianPhone(""), "empty guardian phone should be allowed");
  assert(isValidGuardianPhone("010-1234-5678"), "valid Korean mobile-like phone should pass");
  assert(!isValidGuardianPhone("1234"), "too short phone should fail");
}

function testSanitizeKeepsAtLeastOneEditableContact() {
  const contacts = sanitizeEmergencyContacts([]);

  assert(contacts.length === 1, "settings form should keep one editable empty contact");
  assert(contacts[0].phone === "", "empty editable contact should not invent phone data");
}

function main() {
  testSettingsMigrationAndMasking();
  testMultipleContactsAreLocalOnlyShape();
  testPhoneValidationAllowsEmptyButRejectsTooShort();
  testSanitizeKeepsAtLeastOneEditableContact();
  console.log("settings privacy checks passed");
}

main();
