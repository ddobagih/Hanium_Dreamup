// S19-4: a device-bound session is not a per-request proof of a registered device.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

type Json = Record<string, unknown>;

async function openapi(): Promise<Json> {
  return JSON.parse(await readFile(path.join(process.cwd(), "openapi.json"), "utf8")) as Json;
}

test("S19-4 openapi describes device_id as an installation identifier, never as device proof", async () => {
  const schemas = ((await openapi()).components as Json).schemas as Record<string, Json>;
  for (const name of ["AccountPasswordSessionLogin", "FieldSessionLogin"]) {
    const deviceId = (schemas[name]!.properties as Record<string, Json>).device_id!;
    const description = String(deviceId.description);
    assert.match(description, /per-install identifier/i, name);
    assert.match(description, /not a per-request/i, name);
    assert.match(description, /bearer credential/i, name);
  }
});

test("S19-4 no gateway contract or document claims device attestation for device_id", async () => {
  const sources = [
    await readFile(path.join(process.cwd(), "openapi.json"), "utf8"),
    await readFile(path.join(process.cwd(), "README.md"), "utf8")
  ];
  for (const text of sources) {
    for (const sentence of text.split(/(?<=[.!?。])\s+|\n/)) {
      if (!/device_id|device id|기기 ID|기기별 세션/i.test(sentence)) continue;
      // Any attestation/proof wording next to device_id must be a negation.
      if (/attest|기기 증명|device proof|hardware[- ]backed|registered device key|등록 단말의 키/i.test(sentence)) {
        assert.match(sentence, /not|아닙니다|않습니다|아니며/i, sentence.slice(0, 200));
      }
    }
  }
  const readme = sources[1]!;
  assert.match(readme, /`device_id`는 앱 설치 식별값이며 요청자가 등록 단말의 키를 가졌다는 요청별 증명이 아닙니다/);
});
