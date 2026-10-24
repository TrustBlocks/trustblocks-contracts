# Attestation clauses: drafts for review

Design note, 2026-10-23. **Drafts only. Nothing is stored in the library.** The wording is for the Town Attorney; I have written it to be plain and to say who certifies, under which authority, and what the certification does.

## What I found in the two templates

I read the street-resurfacing pay application and the manager employment contract against their `lifecycle.json`.

**Pay application** (6 events, 7 required certifications):

| Event | Needs | Authority | Does the text say so today? |
|---|---|---|---|
| PayRequestReceived | Receipt | RECEIVE_PAY_APPLICATIONS | No. Nothing about receipt. |
| InspectionCertified | Attestation | CERTIFY_INSPECTIONS | Partly. Each pay item shows "Approved by Engineer", but no officeholder or authority is named. |
| InspectionCertified | Receipt | RECEIVE_INSPECTION_REPORTS | No. |
| PayRequestCertified, PayRequestReturned | Attestation | CERTIFY_PAY_APPLICATIONS | Partly. A signature line "Approved and Payment Recommended" (Director of Public Works, Town Manager). |
| PaymentApproved | Attestation | APPROVE_PAYMENTS | Partly. Same signature block. |
| PaymentCertified | Attestation | CERTIFY_PAYMENTS | Yes. "Payment Certificate", signed by the Finance Officer ("Added by Trustblocks for the Town Attorney's review"). |

**Manager employment contract** (2 events, one authority, CERTIFY_MINUTES):

| Event | Does the text say so today? |
|---|---|
| BoardApprovalRequest | The recital says the governing body voted. It does not say the minutes are certified. |
| AttestationRequest | "duly attested by its Clerk" and an ATTEST line. It does not name the authority. |

So the real gap is not that certification is missing from the paper form. It is that the **paper signature blocks and the lifecycle are two separate descriptions of the same act**, and nothing says they are the same act. Receipts are not mentioned at all.

## What a clause here is

An attestation clause states, in the contract's own words, that a given certification by a given authority is what moves the document to its next state. Its `:clause/states` says which lifecycle events it covers, so the release check can confirm each one is stated.

Each requirement is specific to a template's lifecycle, so I drafted **one clause per group of requirements** rather than one generic clause. The exception is the Board-approval/clerk-attestation pair, which is likely to recur in other contracts the Board approves.

An alternative is one parameterised clause whose events and authority are filled in where it is used. That needs a small change (a use of a clause carrying its own `:states`). I would only do it if a second contract needs the same wording.

Fields used: only ones I saw in the templates (`municipalityType`, `municipalityName`, `governingBodyName`, `clerkTitle`, `financeOfficerTitle`). The pay application names its offices in fixed text, so the drafts do too. **Each needs checking against the model it will require.**

## Drafts

### 1. `pay-request-receipt`
States: `{:event "PayRequestReceived" :authority "RECEIVE_PAY_APPLICATIONS"}`

> A Pay Request is received by the {{municipalityType}} when the officer authorized to receive pay applications records its receipt. The {{municipalityType}}'s record of receipt, made by that officer, is the date from which the time for inspection and review runs.

### 2. `inspection-certification`
States: `{:event "InspectionCertified" :authority "CERTIFY_INSPECTIONS"}`, `{:event "InspectionCertified" :authority "RECEIVE_INSPECTION_REPORTS"}`

> Work for which payment is requested is inspected before it is certified for payment. The officer authorized to receive inspection reports records receipt of the report, and the officer authorized to certify inspections certifies that the work listed as completed has been inspected and accepted. A Pay Request is not treated as inspected until both have been recorded.

### 3. `pay-request-certification`
States: `{:event "PayRequestCertified" :authority "CERTIFY_PAY_APPLICATIONS"}`, `{:event "PayRequestReturned" :authority "CERTIFY_PAY_APPLICATIONS"}`

> After inspection, the officer authorized to certify pay applications certifies that the Pay Request is correct and supported by the inspection, or returns it to the Contractor with the reasons it cannot be certified. A returned Pay Request is not paid; the Contractor may submit a corrected one.

### 4. `payment-approval`
States: `{:event "PaymentApproved" :authority "APPROVE_PAYMENTS"}`

> Payment of a certified Pay Request is approved by the officer authorized to approve payments. Approval is made on the certified Pay Request, and the {{municipalityType}} makes no payment without it.

### 5. `payment-certification`
States: `{:event "PaymentCertified" :authority "CERTIFY_PAYMENTS"}`

> When payment has been made, the {{financeOfficerTitle}}, or the officer authorized to certify payments, certifies the amount paid, the date, and the check or electronic funds transfer number. This certification closes the Pay Request.

### 6. `governing-body-approval` (optional: only contracts the Board approves)
States: `{:event "BoardApprovalRequest" :authority "CERTIFY_MINUTES"}`, `{:event "AttestationRequest" :authority "CERTIFY_MINUTES"}`

> This Agreement takes effect only on action of the {{governingBodyName}} recorded in its minutes. The {{clerkTitle}}, or the officer authorized to certify minutes, certifies that the {{governingBodyName}} took that action, and later attests this Agreement. The {{municipalityType}} of {{municipalityName}} is bound only after that certification.

### 7. `finance-officer-certification` (new, to be drafted)
Most contracts do not go to the Board; the Manager executes them. They still need the Finance Officer's certification, so this clause belongs in the standard set and `governing-body-approval` becomes **optional**, used only by contracts the Board approves. The authority name is not yet known (the pay application has `CERTIFY_PAYMENTS`, which is a different act). I believe NC's pre-audit certificate on obligations is the legal basis; the Town Attorney should confirm before the wording is written.

### 7a. `finance-officer-certification` (draft wording)
States: `{:event "PreAuditCertified" :authority "CERTIFY_PREAUDIT"}` (**event and authority names are proposals**; check them against the OfficeGrants). Not waivable.

> **PRE-AUDIT CERTIFICATE**
> This instrument has been pre-audited in the manner required by the Local Government Budget and Fiscal Control Act.

The statutory sentence is taken unchanged from the street-resurfacing contract. The signature line and date stay as the template has them (`financeOfficerSignature`, `preAuditDate`). That the certificate is a condition is said once, in `conditions`, not here.

### 8. `background` (draft wording, context only)
States: none. It creates no obligation, so it has no lifecycle event.

> This Agreement is made by the {{municipalityType}} of {{municipalityName}}, {{stateName}}, acting through its {{governingBodyName}}, with the party named below, for the purpose stated in the Agreement. This Background gives context. It creates no obligation, and nothing in it changes the terms that follow.

### 9. `conditions` (draft wording)
States: every certification the lifecycle requires. The list below is written by hand for each template for now. Generating it from the lifecycle is the next step, and until then the release check confirms each one is covered.

> The following certifications are conditions of this Agreement. Each is made by the officer who holds the stated authority, and until it is made the step it governs has not happened.
> 
> *[one line per required certification, for example: "Pre-audit certificate, by the Finance Officer (G.S. 159-28)."]*
> 
> No condition in this section may be waived, except as follows. *[Where the lifecycle allows a waiver: "The {authority} may waive the {event} certification, in writing, and the waiver is recorded with that officer's signature."]* *[Otherwise: "No condition in this section is waivable."]*

Clauses 1 to 6 may then drop their own "not treated as ... until" sentences where `conditions` already says it; I have not edited them yet.

## Decisions (2026-10-24)

- The wording is acceptable as a starting point. The contract does **not** say how the certification is recorded (the sentence I had considered is dropped).
- `governing-body-approval` is optional. A contract executed by the Manager has a lifecycle with no Board events, and the release check only asks for what its own lifecycle requires. The Finance Officer certification is required for all.
- **Attestation dialog, like an Acrobat signature.** The officer chooses to attest; the officer's handwritten signature image is placed on the signature line, followed in small print by `CG_Atest` and the short hash (or `CG_Atest` alone, with the hash beside it). The image is presentation. The authority is the signed credential; the mark points at it.
- **Documents we do not author.** An inspector's report arrives as a PDF. The received file is hashed as received and that hash is the Attestation's `documentHash`. The original bytes are never altered; the mark goes on a receipt stamp or cover sheet.
- **Automated minimum review.** A model (Jev or similar) reads the submitted PDF and returns yes or no against a short checklist. It is a **flag for the officer**, stored as its own record, never as the attestation. The officer decides, and attests that the document was received and is satisfactory.

### What this needs

1. **Subject of the attestation.** For our contracts, the release SHA (`attestation-subject`). For received documents, the SHA-256 of the file. The mark must be computed from the document *before* any mark is placed, or the hash changes itself.
2. **Signature image** stored per identity, as an asset the officer uploads or draws once. Personal data: access limited to that officer and the renderer.
3. **A `received-document` record:** file bytes (or reference), hash, who submitted it, when, the checklist used, the flag result and model identity. Immutable, like a release.
4. **Rendering** the mark onto Word/PDF output.
5. **Settled (2026-10-24):** contractual documents are public records, so sending them to an external model is acceptable by default. The exception is anything marked confidential or attorney work product (below), which is never sent out.

### Classification mark (decided 2026-10-24)

Everything is public. There is no classification by default, and no field to fill in.

- A document or draft may carry an **optional mark**: `confidential` or `attorney-work-product`. Unmarked means public.
- A mark shows as a stamp on the output and a chip in the console. A marked document is not sent to an external model.
- Setting or lifting a mark is an explicit act by a named person and is recorded.
- Truly confidential material belongs outside this system. The mark is for the rare case, not a way to keep such material here.
- The NC Public Records Act exceptions are narrow and time-limited, so the Town Attorney decides the stamp wording. The mark is a handling marker, not a legal determination.

## Waiver (decided and built 2026-10-24)

A required certification cannot be waived unless the contract says so. The pre-audit certificate (G.S. 159-28) is not waivable and gets no waiver wording.

- **Lifecycle:** a requirement may carry `"waivableBy": "AUTHORITY"`. Absent means not waivable.
- **Clause:** a `:clause/states` entry may carry `:waivable-by "AUTHORITY"`, meaning its text grants that waiver.
- **Release check** (`lifecycle-check/waivers`, reported with the other findings): `:waiver-unstated` when the lifecycle allows a waiver no clause grants; `:waiver-not-allowed` when a clause grants one the lifecycle does not. `:require-stated?` refuses either.
- **Templates repo:** `waivableBy` is added to `Certification` in `trustblocks-templates` `shared/model/lifecycle.cto` and its three vendored copies, on branch `feat/waivable-by` (commit `3d8c363`, local, not pushed). `bb check` passes.
- **Not done:** the recorded waiver act at runtime. Drafts for `conditions` and `background` are above (7a, 8, 9); nothing is stored in the library.

## Received documents and the review flag: what already exists

I proposed a `received-document` record. The app already has it, so none was built:

- `xtdb/document`: a file stored by content, id `sha256:<hex>`, the same form as `release/attestation-subject`.
- `xtdb/receipt`: an officeholder's signed receipt of a stored document on a matter.
- `xtdb/advisory`: an AI agent's signed note on a document, under an ADVISE designation. It moves nothing.

Added this round:
- `advise!` takes an optional `verdict` ("pass" or "flag") and `checklist`, and `identity.cto`'s `Advisory` carries them. A pass is advice only; the officeholder decides.
- `certify!` takes `:require-known-subject?`, which refuses an attestation whose `documentHash` names neither a stored file nor a stored release (`cert/subject-kind`). Off by default; the certify form still takes a typed reference.

Not built: calling a model to produce the verdict, the signature-image mark and its rendering, and the optional confidential / work-product mark.
