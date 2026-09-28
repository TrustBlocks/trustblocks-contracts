// trustblocks-logic.js, loaded as the Template Playground's logic worker
// loads it -- from its text, through new Function, with no module system
// and no window -- and driven through a pay request's whole life, with the
// template's own logic, lifecycle and model from trustblocks-templates.
//
// Run by bin/logic-bundle after the release build.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";

new Function(readFileSync("target/trustblocks-logic.js", "utf8"))();
const L = globalThis.TrustblocksLogic;

const templates = process.env.TRUSTBLOCKS_TEMPLATES ?? "../trustblocks-templates";
const dir = join(templates, "packages/street-resurfacing/templates/pay-application");
const read = (f) => readFileSync(join(dir, f), "utf8");

const logic = read("logic/clause.clj");
const lifecycle = JSON.parse(read("lifecycle.json"));
const model = read("model/model.cto");
const ns = "com.trustblocks.municipal.construction.payapplication@1.0.0.";
const data = {
  ...JSON.parse(read("sample.json")),
  payItems: [
    { payItem: "3.1", unit: "SY", unitPrice: 4.25, quantityThisPeriod: 4800, amountThisPeriod: 20400,
      quantityToDate: 4800, amountToDate: 20400 },
  ],
  grossAmount: 20400, retainagePercent: 5, retainageAmount: 1020, previousPayments: 0,
  otherDeductions: 0, totalDeductions: 1020, netAmountDue: 19380,
};
const step = (state, event, fields = {}, extra = {}) =>
  L.trigger({ logic, lifecycle, model, data, state, request: { $class: ns + event, ...fields },
              now: "2026-07-24T15:00:00.000Z", ...extra });

test("the global, and the vocabulary's own check", () => {
  assert.equal(typeof L.version, "string");
  assert.equal(L.check(logic), null);
  assert.match(L.check("(rand)"), /rand/);
  assert.equal(L.identityField(model, "ContractorPayRequest"), "payRequestId");
  assert.equal(L.identityField(model, "ContractorPayRequestState"), "payRequestId");
});

test("a pay request, received to paid", () => {
  assert.deepEqual(L.nextSteps(lifecycle, null).map((s) => s.event), ["PayRequestReceived"]);

  const received = step(null, "PayRequestReceived");
  assert.equal(received.ok, true, received.error);
  assert.equal(received.state.status, "RECEIVED");
  assert.equal(received.state.payRequestId, data.payRequestId);
  assert.equal(received.state.$class, ns + "ContractorPayRequestState");
  assert.deepEqual(received.certifiedAs, { credential: "Receipt", authority: "RECEIVE_PAY_APPLICATIONS" });
  assert.equal(received.response.to, "RECEIVED");

  // Certified before the engineer approved anything: the clause refuses.
  const inspectedNothing = step(step(received.state, "InspectionCertified", { approvedPayItems: [] }).state,
                                "PayRequestCertified");
  assert.equal(inspectedNothing.ok, false);
  assert.equal(inspectedNothing.code, "clause-refused");
  assert.equal(inspectedNothing.error, "Not approved by the engineer: pay item 3.1");

  const inspected = step(received.state, "InspectionCertified", { approvedPayItems: ["3.1"] });
  assert.equal(inspected.state.status, "INSPECTED");
  assert.deepEqual(inspected.state.approvedPayItems, ["3.1"]);

  let state = inspected.state;
  for (const [event, status] of [["PayRequestCertified", "CERTIFIED"], ["PaymentApproved", "APPROVED"],
                                 ["PaymentCertified", "PAID"]]) {
    const r = step(state, event);
    assert.equal(r.ok, true, r.error);
    assert.equal(r.state.status, status);
    assert.deepEqual(r.state.approvedPayItems, ["3.1"], "the clause's state is kept through later steps");
    state = r.state;
  }
  assert.deepEqual(L.nextSteps(lifecycle, state), []);
});

test("what the lifecycle refuses", () => {
  const early = step(null, "PaymentApproved");
  assert.equal(early.ok, false);
  assert.equal(early.code, "no-transition");
  assert.equal(early.error, "PaymentApproved cannot happen before the lifecycle has begun");

  const wrong = step(null, "PayRequestReceived", {},
                     { certification: { credential: "Attestation", authority: "APPROVE_PAYMENTS" } });
  assert.equal(wrong.code, "certification-required");
  assert.equal(wrong.requires[0].authority, "RECEIVE_PAY_APPLICATIONS");
});

test("figures that disagree are refused with the figures, as the app writes them", () => {
  const r = L.trigger({ logic, lifecycle, model, request: { $class: ns + "PayRequestCertified" },
                        data: { ...data, netAmountDue: 70000 },
                        state: { $class: ns + "ContractorPayRequestState", payRequestId: data.payRequestId,
                                 status: "INSPECTED", approvedPayItems: ["3.1"] },
                        now: "2026-07-30T15:00:00.000Z" });
  assert.equal(r.error, "The figures do not agree: the net amount due is $70000, not $19380");
});

test("no now given: the host's current time, and the result says so", () => {
  const r = L.trigger({ logic, lifecycle, model, data, state: null,
                        request: { $class: ns + "PayRequestReceived" } });
  assert.equal(r.ok, true);
  assert.match(r.now, /^\d{4}-\d{2}-\d{2}T/);
});
