# ADR: MuPDF License And DOCX/CBZ Optional Formats

> Date: 2026-06-23  
> Status: accepted for current pure-reading backfill  
> Scope: Android optional DOCX/CBZ engines, base APK packaging, license gate  
> Note: this is an engineering release decision, not legal advice. Revisit with legal/commercial review before shipping MuPDF-linked binaries.

## Context

Android v4 keeps `:render:mupdf` as an optional path for DOCX/CBZ and explicitly forbids MuPDF from entering the base APK. The remaining gap was a concrete license/route decision before anyone enables that module.

Official sources checked on 2026-06-23:

- MuPDF documentation: MuPDF is licensed under the GNU AGPL and warns that systems built with it may need to release full source code.
- Artifex licensing: Artifex products are dual-licensed under AGPL or commercial agreements; if AGPL requirements cannot be met, a commercial license is required.
- MuPDF product formats page: current MuPDF Core input formats include PDF, ePUB, images, SVG/PNM/PAM, CBZ, and XPS; DOCX is not listed there.
- PyMuPDF docs: standard PyMuPDF supports PDF/XPS/EPUB/MOBI/FB2/CBZ/SVG/TXT/images; Office DOC/DOCX support is listed under PyMuPDF Pro.

Source links:

- https://mupdf.readthedocs.io/en/1.27.0/license.html
- https://artifex.com/licensing
- https://mupdf.com/
- https://pymupdf.readthedocs.io/en/latest/how-to-open-a-file.html

## Decision

1. **Base APK remains MuPDF-free.** No MuPDF dependency, `libmupdf*.so`, or Artifex binary may be added to the base Android APK.
2. **No AGPL MuPDF path is enabled in the current milestone.** LinReads will not ship MuPDF-linked binaries unless the project either:
   - commits to full AGPL compliance for the distributed app and corresponding source, or
   - obtains a commercial Artifex license.
3. **DOCX is deferred.** Current official MuPDF Core format lists do not establish DOCX as a standard Android/MuPDF Core input. Office DOC/DOCX appears in PyMuPDF Pro/conversion-layer documentation, so `OPT-02` is deferred until a commercial/pro-capable route, packaging plan, and product need are approved.
4. **CBZ is deferred for this pure-reading backfill.** MuPDF does support CBZ, but using it would still introduce AGPL/commercial licensing and native binary cost. If CBZ becomes a priority, prefer evaluating a first-party ZIP image pager before MuPDF. MuPDF CBZ remains allowed only behind the license gate above.
5. **If MuPDF is revisited later, it must be isolated.** Requirements: `:render:mupdf` only, feature flag or dynamic delivery/ABI split, no cold-start initialization, explicit notices/source-offer or commercial-license record, app-store release checklist, and CI proof that the base APK contains no `libmupdf`.

## Consequences

- `OPT-01` is closed as `DONE`: the AGPL/commercial/alternative decision is recorded.
- `OPT-02` is `DEFERRED`: DOCX optional engine is not implemented in this milestone.
- `OPT-03` is `DEFERRED`: CBZ optional engine is not implemented in this milestone.
- Future work can still implement DOCX/CBZ, but only through a new ADR update that names the chosen engine, license posture, package-size budget, and smoke-test plan.

## Revisit Conditions

- User explicitly prioritizes DOCX/CBZ over remaining reader quality gates.
- Commercial licensing is available and acceptable.
- A no-AGPL alternative is selected, such as a lightweight CBZ ZIP image pager.
- Dynamic delivery / ABI split pipeline exists and can prove base APK remains MuPDF-free.
