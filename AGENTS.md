# LinReads Codex Hybrid Delegation Workflow

## Delegated execution

- DeepSeek V4 Flash is the default text-only worker for bounded audits, implementation, debugging, testing, and review tasks. Grok remains an authorized write-capable worker only when the task packet and user authorization allow it. GPT-5.6 Luna is reserved for multimodal, high-ambiguity, cross-module, or tool-orchestration work when that provider is available to the worker runtime.
- Follow `CLAUDE.md` for architecture, build commands, language, and mandatory skill routing.
- Keep work bounded to the delegated task. Preserve all unrelated tracked and untracked changes.
- Local edits, ADB/device diagnostics, log capture, static file inspection, and non-build tooling are allowed when required by the task.
- Effective 2026-07-31 after the current handoff, local Android builds/tests/assemble tasks are disabled for all agents and Grok workers. New APKs, full regression, R8/minification, and Android test artifacts must be produced by GitHub Actions/cloud build only.
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

## Hybrid delegation strategy

- **DeepSeek V4 Flash first:** use it for text-only source discovery, static code audit, logs, UI XML, `gfxinfo`/Perfetto summaries, structured extraction, bounded implementation, targeted tests, and focused self-review. Never send screenshots, image paths, video frames, base64 media, or visual-inspection requests to DeepSeek.
- **GPT-5.6 Luna escalation:** use it for image or screenshot interpretation, multimodal evidence, complex cross-module reasoning, difficult tool orchestration, or an independent review when DeepSeek results conflict or the risk is high. If Luna is not exposed by the current worker runtime, keep the task with the main agent or use the configured text-capable fallback; do not fake a Luna dispatch.
- **Grok lane:** use Grok for authorized write-capable execution when its packet is explicitly requested or when the accepted project workflow selects it. Grok does not replace the main agent's architecture, scope, acceptance, or integration ownership.
- **Normal mode flow:** split read-only discovery first, select the smallest independent conflict domains, then assign one writer per domain. Use DS for the broad text pass, Luna for multimodal/ambiguous review, and only then integrate and verify in the main agent. Parallel children must use isolated worktrees or provably disjoint files.
- **Escalation triggers:** escalate from DS to Luna/main-agent review when evidence is visual, the worker reports unresolved ambiguity, two workers disagree, a shared contract or migration is affected, or the change is release/security critical.
- **No forced parallelism:** if the work is tightly coupled or has no safe independent stream, run one worker or no worker. Do not create a planning child merely to satisfy the hybrid label.

## Low-cost mode

- Set `Cost mode: cost-saving` only when the user explicitly requests lower cost, token savings, or reduced GPT/Sol usage. In this mode, prefer DeepSeek V4 Flash for text-only execution and Grok for authorized write-capable slices; use Luna only for the escalation triggers above.
- In low-cost mode, the main Sol agent **should not directly perform broad implementation, debugging, or routine test edits**. Delegate those concrete operations inside bounded packets and let the worker return the diff, checks, and residual risks.
- Sol remains responsible for the overall plan, architecture, conflict ordering, scope control, acceptance, independent risk review, and final integration. Direct Sol edits are limited to small integration glue, conflict resolution, security/credential handling, or an explicitly user-approved exception.
- A child that only reports a plan or partial tool output is not completion. Require changed files, exact checks, failures, and residual risks before accepting the slice.

## Cost-saving delegation

- When a Codex task packet explicitly sets `Cost mode: cost-saving`, the selected worker (DeepSeek by default, or authorized Grok for a write-capable slice) owns the concrete work inside that Codex-defined slice: detailed investigation, relevant-file discovery, local implementation choices consistent with the accepted direction, implementation, debugging, targeted tests, and focused self-review.
- Codex/Sol remains responsible for the overall solution, architecture, task decomposition, sequencing, scope decisions, and acceptance. Workers must report plan conflicts or cross-cutting tradeoffs instead of silently changing direction.
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
