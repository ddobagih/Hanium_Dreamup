import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";
import { parsePasswordGrant } from "../src/account-relay.js";

test("Android and Backend shared 53 email vectors preserve local case and exact ASCII wire", async () => {
  const fixture = JSON.parse(await readFile(path.resolve(process.cwd(), "../../contracts/test-vectors/account-email.json"), "utf8")) as {
    schema: string; vectors: { input: string; canonical: string | null }[];
  };
  assert.equal(fixture.schema, "walkmate.account-email-test-vectors.v1");
  assert.equal(fixture.vectors.length, 53);
  for (const vector of fixture.vectors) {
    const result = parsePasswordGrant({ grant_type: "password", email: vector.input,
      password: "preserve-this-value", remember_me: false });
    assert.ok(result);
    assert.equal(result.email, vector.canonical, JSON.stringify(vector.input));
    assert.equal(result.password, "preserve-this-value");
    assert.equal(result.rememberMe, false);
  }
});
