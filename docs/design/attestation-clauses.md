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
5. **A decision for you:** sending contractor PDFs to an external model sends their content to a third party. The town may have rules about that, and the contractor's report may include personal information. Options are a local model, or an external one for non-sensitive document classes only.

## Questions for you and the Town Attorney

1. Are the wordings acceptable as a starting point, and who edits them?
2. Should the pay application's **paper signature blocks** be tied to these clauses (so "Director of Public Works" is the holder of `CERTIFY_PAY_APPLICATIONS`)? The templates use job titles; the lifecycle uses authorities. The mapping office to authority lives in the OfficeGrant records, not in the template.
3. `RECEIVE_PAY_APPLICATIONS` and the other authorities: do the names match what is in your real OfficeGrants?
4. Do you want the check strict (`:require-stated?`) for new templates from now on, and report-only for the ones that exist?
