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

### 6. `governing-body-approval`
States: `{:event "BoardApprovalRequest" :authority "CERTIFY_MINUTES"}`, `{:event "AttestationRequest" :authority "CERTIFY_MINUTES"}`

> This Agreement takes effect only on action of the {{governingBodyName}} recorded in its minutes. The {{clerkTitle}}, or the officer authorized to certify minutes, certifies that the {{governingBodyName}} took that action, and later attests this Agreement. The {{municipalityType}} of {{municipalityName}} is bound only after that certification.

## Every clause states how the act is recorded

I considered adding one sentence to each: *"The certification is recorded as a signed credential issued by the officeholder under the authority named, and is valid only for the period the officeholder held that authority."* It describes what the system does, but it is also a statement of the Town's record-keeping, so it is a question for the Town Attorney whether it belongs in the contract text. I left it out of the drafts.

## Questions for you and the Town Attorney

1. Are the wordings acceptable as a starting point, and who edits them?
2. Should the pay application's **paper signature blocks** be tied to these clauses (so "Director of Public Works" is the holder of `CERTIFY_PAY_APPLICATIONS`)? The templates use job titles; the lifecycle uses authorities. The mapping office to authority lives in the OfficeGrant records, not in the template.
3. `RECEIVE_PAY_APPLICATIONS` and the other authorities: do the names match what is in your real OfficeGrants?
4. Do you want the check strict (`:require-stated?`) for new templates from now on, and report-only for the ones that exist?
