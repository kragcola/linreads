# LinReads Codex-to-Grok Workflow

## Delegated execution

- Grok is an authorized write-capable worker for tasks delegated by Codex in this repository.
- Follow `CLAUDE.md` for architecture, build commands, language, and mandatory skill routing.
- Keep work bounded to the delegated task. Preserve all unrelated tracked and untracked changes.
- Local edits, builds, tests, emulators, and diagnostics are allowed when required by the task.
- Do not commit, push, publish OTA builds, create releases, or contact external systems unless the delegated prompt explicitly requests it.

## Parallel workflow

- For each non-trivial task, check whether at least two independent bounded workstreams can reduce wall-clock time.
- Allocate background subagents adaptively like a Codex main agent; never require a fixed count or an upfront agent roster.
- Use zero for trivial or tightly coupled work, one for a single independent side track, and several for genuinely disjoint workstreams. Add workers progressively as new separable work emerges, up to eight concurrent background subagents when supported.
- Treat eight as a ceiling rather than a target. Do not force a planning subagent, fill idle slots, create fixed waves, or duplicate a live workstream.
- Keep the top-level agent on the critical path and assign one writer per file or module.
- Use isolated worktrees for concurrent writing agents. Shared-workspace subagents must be read-only or own disjoint files.
- Integrate all delegated results and run the smallest meaningful verification before reporting completion.
- Never create duplicate replacement agents for the same live workstream; reconnect or resume the existing one first.

## Cost-saving delegation

- When a Codex task packet explicitly sets `Cost mode: cost-saving`, Grok owns the concrete work inside that Codex-defined slice: detailed investigation, relevant-file discovery, local implementation choices consistent with the accepted direction, implementation, debugging, targeted tests, and focused self-review.
- Codex remains responsible for the overall solution, architecture, task decomposition, sequencing, scope decisions, and acceptance. Grok must report plan conflicts or cross-cutting tradeoffs instead of silently changing direction.
- Return a consolidated diff summary, implementation rationale, exact checks, and residual risks so Codex can limit its hands-on work to leadership, architecture and scope acceptance, code-quality review, risk-based independent verification, and final integration.
- Avoid routine clarification and unnecessary broad work. Make conservative in-scope assumptions without expanding authority, and never trade away required LinReads skill routing, correctness, or verification to save cost.

## Handoff format

- Report the outcome first, then changed files, verification evidence, and remaining risks.
- If a task cannot finish, name the exact blocker and the last verified state.

## Emulator Screenshot Evidence Budget

- The top-level/main agent must not attach, embed, forward, or otherwise load screenshots, images, contact sheets, or video frames into the main conversation context. It must not call image-returning inspection tools from the main thread.
- This hard gate applies to the current task as soon as these instructions are loaded. It does not prohibit capturing or generating image files on disk; the main agent may reference absolute evidence paths and consume text, JSON, hashes, OCR, UI hierarchy, frame metrics, and pixel-diff summaries.
- Visual inspection must be delegated to a background subagent with `fork_turns="none"`. That subagent may inspect the minimum necessary local images, but its handoff to the main agent must contain text/JSON conclusions and evidence paths only, with no attached or embedded images.
- Only an explicit user instruction in the current turn may waive the main-agent image-context prohibition for named evidence.
- Keep raw emulator screenshots on disk and reference their evidence directory in the handoff; do not attach the full capture set to the conversation.
- A delegated visual-inspection subagent may inspect at most 4 screenshots per turn and must keep its combined image payload under 20 MB.
- Do not re-attach screenshots already inspected. For animations or page turns, attach only before, first-frame, one representative middle frame, and after.
- Prefer `gfxinfo`, Perfetto/frame-timeline data, PSS, UI hierarchy XML, OCR, and pixel-diff summaries over repeated full-screen images.
- Resize full-screen captures to a 1280 px long edge when legibility allows; use small cropped regions for text or control details.
- After 8-12 cumulative screenshots in one visual-inspection context, or when its serialized request approaches 60 MB, write a checkpoint containing conclusions, failures, and evidence paths before continuing with a fresh `fork_turns="none"` subagent.
- If a tool produces a large screenshot batch, select representative frames locally before showing images to the model. Never use the conversation as the raw evidence store.
