# Code Review Practices

A guide for conducting effective, constructive, and thorough code reviews.

---

## 1. The Standard of Review

### 1.1 The Core Principle

**Approve a change once it clearly improves the overall health of the codebase, even
if it is not perfect.**

This is the senior principle that governs all other review guidance. There is no such
thing as "perfect" code — there is only *better* code. The reviewer's job is not to
block progress toward an unattainable ideal, but to ensure continuous, positive
movement in code quality.

### 1.2 Balancing Progress and Rigor

Two forces must be held in tension:

- **Forward progress.** If changes never merge, the codebase never improves.
  Reviewers who create unreasonable friction disincentivize future improvements.
- **Code health.** Codebases degrade through many small concessions over time,
  especially under schedule pressure. Every reviewer is a steward of the code they
  review and shares ownership of its long-term quality.

A change that, taken as a whole, improves maintainability, readability, and
understandability should not be delayed for days because it isn't flawless.

### 1.3 Distinguishing Severity

Not every piece of feedback carries the same weight. Use explicit labels to signal
intent and help authors prioritize:

| Label | Meaning |
|---|---|
| *(no label)* | A required change; the reviewer considers this necessary for approval. |
| **`Nit:`** | A minor polish point. Technically better, but not a blocker. |
| **`Optional:` / `Consider:`** | A suggestion the reviewer thinks is a good idea, but not strictly required. |
| **`FYI:`** | Informational. No action expected in this change, but relevant for future work. |
| **`Question:`** | A request for clarification, not necessarily a request for a code change. |

Labeling severity prevents misunderstandings. Without labels, authors may interpret
every comment as mandatory — or, conversely, dismiss important feedback as nitpicking.

### 1.4 Principles for Resolving Disagreements

When reviewer and author disagree, apply these principles in order:

1. **Technical facts and data overrule opinions and personal preferences.** Bring
   evidence — benchmarks, profiling data, specification references — not feelings.
2. **The style guide is authoritative on matters of style.** Any purely stylistic
   point not covered by the style guide is a matter of personal preference; default
   to consistency with the existing code, then to the author's choice.
3. **Software design questions are grounded in engineering principles, not personal
   taste.** If the author can demonstrate, through data or sound reasoning, that
   multiple approaches are equally valid, defer to the author.
4. **When no other rule applies, prefer consistency with the surrounding codebase,**
   as long as doing so does not degrade code health.

If consensus cannot be reached through discussion, escalate — to a broader team
discussion, a tech lead, a code owner, or an engineering manager. Never let a change
stall indefinitely because of an unresolved disagreement. If the discussion moves to a
synchronous format (call, meeting), record the outcome as a comment on the review for
future readers.

---

## 2. What to Look For

### 2.1 Design

This is the most important dimension. Evaluate:

- **Coherence.** Do the interactions between components in the change make sense?
- **Placement.** Does this change belong in this codebase, or in a shared library, a
  separate service, or a different layer?
- **Integration.** Does it fit naturally with the existing system architecture?
- **Timing.** Is now the right time to add this functionality, given the project
  roadmap and the state of surrounding code?
- **Adherence to SOLID principles.** Is the design modular, with clear
  responsibilities and minimal coupling?

### 2.2 Functionality

- **Correctness.** Does the code do what the author intended? Is what the author
  intended the right thing for users of this code — both end-users and other
  developers?
- **Edge cases.** Think about boundary conditions, empty inputs, null values,
  off-by-one errors, overflow, and unexpected types.
- **Concurrency.** If the change involves parallelism, threading, or async
  operations, carefully reason about race conditions, deadlocks, and atomicity.
  These defects are extremely difficult to catch through testing alone and demand
  deliberate thought during review.
- **User-facing impact.** For UI changes, visual changes, or changes to user-visible
  behavior, consider requesting a demo from the author. Reading a diff is often
  insufficient to judge user impact.

### 2.3 Complexity

Evaluate complexity at every level — individual expressions, functions, classes, and
the overall change:

- **Readability.** Can another engineer understand this code quickly and confidently?
  If not, it is too complex.
- **Modifiability.** Are future developers likely to introduce bugs when calling or
  modifying this code?
- **Over-engineering.** Be vigilant against premature generalization, speculative
  abstractions, and features not required by current needs. Encourage solving the
  problem at hand, not a hypothetical future problem. The future problem should be
  solved when it arrives and its actual shape is visible.

### 2.4 Tests

- **Presence.** Changes to production code should include corresponding tests (unit,
  integration, or end-to-end) in the same change, unless there are exceptional
  circumstances.
- **Correctness.** Tests are code too — they are not self-validating. Verify that
  assertions are meaningful, that tests will actually fail when the code under test
  breaks, and that they do not produce false positives.
- **Coverage of important paths.** Check for tests on critical paths, error paths,
  edge cases, and boundary conditions — not just the happy path.
- **Readability.** Tests should be clear and maintainable. Do not accept
  unreasonable complexity in test code simply because "it's just tests."
- **Test isolation.** Tests should be independent and not rely on execution order or
  shared mutable state.

### 2.5 Naming

Good names are long enough to communicate what an item is or does, without being so
long that they impede readability. Evaluate names for variables, functions, classes,
files, and packages. A name that requires a reader to look up the implementation to
understand its purpose is too opaque.

### 2.6 Comments and Documentation

- **Comments should explain *why*, not *what*.** If code needs a comment to explain
  what it does, the code should usually be simplified first. Exceptions include
  regular expressions, complex algorithms, non-obvious performance optimizations,
  and workarounds for external constraints.
- **Stale comments.** Check whether pre-existing comments are now outdated, whether
  TODOs can be resolved, or whether comments advise against the very change being
  made.
- **API and module documentation.** Public interfaces should document purpose,
  usage, parameters, return values, and behavioral contracts.
- **Associated documentation.** If the change affects how users build, test,
  interact with, or release code, verify that READMEs, guides, and generated
  reference docs are updated. If code is deprecated or deleted, check whether
  documentation should be as well.

### 2.7 Style and Consistency

- **Style guide compliance.** Enforce the project's adopted style guide. For
  purely stylistic points not covered by the guide, default to consistency with
  existing code, then to the author's preference. Never block a change solely on
  personal stylistic preference.
- **Separation of concerns in changes.** Large reformatting or style cleanups
  should not be combined with functional changes. They make the diff hard to read,
  complicate reverts, and obscure the intent of the change. Request that they be
  submitted separately.

### 2.8 Security

Apply increased scrutiny to any code that touches authentication, authorization,
cryptography, data validation, or sensitive data handling. Key areas:

- **Injection prevention.** Are queries parameterized? Is user input validated and
  sanitized? Is output encoded before rendering?
- **Authentication and session management.** Are credentials stored securely (hashed,
  salted)? Are sessions invalidated on logout? Are tokens high-entropy?
- **Authorization.** Does the code verify per-resource permissions? Is the default
  posture "deny"?
- **Secrets management.** Are API keys, tokens, and credentials kept out of source
  code, using environment variables or a secret management service?
- **Sensitive data in logs.** Ensure passwords, tokens, and PII are never logged.
- **Dependency vulnerabilities.** Are third-party libraries scanned for known CVEs?
- **Error disclosure.** Are detailed internal errors (stack traces, DB schemas)
  suppressed in user-facing responses?

If you are not confident in your ability to evaluate the security implications of a
change, ensure that a qualified reviewer is assigned.

### 2.9 Performance and Scalability

Performance review should be proportional to the risk profile of the change:

- **Algorithmic complexity.** Are time and space complexities appropriate for
  expected input sizes? Are there unnecessary iterations or redundant computations?
- **Data access.** Are database queries indexed? Are there N+1 query problems? Is
  only necessary data being fetched?
- **Resource management.** Does the code avoid memory leaks, excessive object
  creation, and holding large datasets in memory unnecessarily?
- **Caching.** Are expensive computations or frequently accessed resources cached
  appropriately, with a defined invalidation strategy?
- **Network calls.** Are external requests batched, async where appropriate, and
  guarded by timeouts?
- **Hard-coded limits.** Are there any fixed-size assumptions that will break as the
  system grows?
- **Statelessness.** Does the code depend on local state in a way that would prevent
  horizontal scaling?

### 2.10 Error Handling

- **No silent failures.** Every failure path should be handled — through exceptions,
  error codes, or logging — not swallowed.
- **Graceful degradation.** When a sub-component or external dependency fails, the
  system should fail safely and, where possible, continue to serve.
- **Correct abstraction level.** Exceptions should be caught and handled at the
  appropriate layer, not caught too early (losing context) or too late (crashing the
  application).
- **User-facing error messages.** Messages shown to users should be helpful and
  actionable without revealing internal system details.

---

## 3. How to Navigate a Change

An efficient review process follows a structured sequence, especially for changes
spanning multiple files.

### Step 1: Assess the Change at a High Level

Read the description and understand the intent. Ask:

- **Does this change make sense?** Is it aligned with the project's direction?
- **Is it well-scoped?** Does the description clearly explain *what* was changed and
  *why*?

If the change fundamentally shouldn't happen — for example, it modifies a system
scheduled for removal — respond immediately with a clear explanation and, if possible,
a constructive alternative. Be courteous: acknowledge the work that went into the
change, even when rejecting it.

If you are seeing repeated changes that conflict with the project's direction, address
the upstream process — improve planning, onboarding documentation, or contributor
guidelines so that contributors get earlier feedback on direction.

### Step 2: Examine the Core of the Change

Identify the file or files that carry the largest logical change — this is the heart
of the review. Read these first. They provide context that makes the rest of the
change faster to evaluate.

If major design problems are evident in the core, **communicate them immediately**,
even if you haven't reviewed the rest of the change. Reasons:

- The author may already be building follow-up work on top of this design. Early
  feedback prevents compounding wasted effort.
- Significant redesigns take time. The sooner the author is aware, the better they
  can manage their schedule.

If the change is so large that you cannot identify the core, ask the author what to
look at first — or ask them to split the change into smaller, independently
reviewable pieces.

### Step 3: Review the Remainder Systematically

Once the core design is validated, review the remaining files in a logical sequence.
Strategies:

- **Tests first.** Reading tests before the implementation gives you a specification
  of intended behavior against which to evaluate the code.
- **Tool order.** If no logical ordering is obvious, reviewing files in the order
  presented by the code review tool is a pragmatic default.
- **Note what you reviewed.** If you are one of multiple reviewers, comment explicitly
  on which parts you reviewed so that coverage is clear and nothing falls through
  the cracks.

---

## 4. How to Write Review Comments

### 4.1 Be Kind and Professional

Code review comments are a form of professional communication. The goal is always to
make the code better and, over time, to help the entire team grow.

- **Comment on the code, never on the person.** Avoid "you" language that could feel
  like a personal judgment. Compare:
  - ❌ *"Why did you use threads here when there's obviously no benefit?"*
  - ✅ *"The concurrency model here adds complexity without a measurable performance
    benefit. Single-threaded execution would be simpler and easier to maintain."*
- **Assume positive intent.** If something looks wrong, assume the author had a
  reason and ask about it before criticizing.
- **Avoid condescending language.** Remove words like "just," "simply," "obviously,"
  and "easy" — they implicitly belittle the author's effort and judgment.
- **Use "I" statements and questions.** Frame feedback as your perspective:
  *"I find this part difficult to follow — could we simplify?"* rather than
  *"This is confusing."*

### 4.2 Explain Your Reasoning

Always explain *why* you are making a comment, not just what you want changed.
Understanding the underlying principle helps the author evaluate the suggestion,
learn from it, and apply it in future work. When relevant, link to documentation,
style guide sections, or established patterns in the codebase.

### 4.3 Strike a Balance Between Direction and Autonomy

- **Point out problems and let the author decide on solutions** when the choice is
  implementation-level. The author is closer to the code and may find a better
  approach.
- **Provide concrete suggestions, examples, or code snippets** when the path forward
  is non-obvious, when you are mentoring a less experienced developer, or when time
  pressure makes it more efficient. The primary goal is to get the best possible
  change; a secondary goal is to grow the author's skills.

### 4.4 Demand Clarity in the Code, Not in the Review Thread

If you ask the author to explain a piece of code and their response is only a
comment in the review tool, push back. The explanation should result in **clearer
code** — better naming, simplification, or an in-code comment — so that future
readers benefit, not just the current reviewer.

Explanations written only in review comments are lost to future maintainers. They
are acceptable only when the reviewer is unfamiliar with the domain and the code would
be clear to its normal audience.

### 4.5 Acknowledge Good Work

Reviews should not focus exclusively on problems. When you see something done well —
a clean refactor, thorough test coverage, a particularly elegant solution — say so,
and explain why it's good. Positive reinforcement of good practices is one of the
most effective forms of mentoring.

---

## 5. Mentoring Through Review

Code review is one of the most effective venues for knowledge transfer on a team.
Reviewers should feel free to share insights about language features, framework
idioms, design patterns, or general software engineering principles.

When a comment is purely educational and not a requirement for the current change,
label it clearly — e.g., `Nit:` or `FYI:` — so the author knows it is optional.

Over time, effective mentoring through review raises the baseline quality of
contributions and reduces the burden of future reviews.

---

## 6. Summary Checklist for Reviewers

Use this as a quick reference during review. It is not exhaustive — apply judgment
and adjust depth to the risk profile of the change.

- [ ] **Design:** Is the change well-designed and appropriately placed?
- [ ] **Functionality:** Does it work correctly, including edge cases and
  concurrency?
- [ ] **Complexity:** Is the code as simple as it can be? No over-engineering?
- [ ] **Tests:** Are tests present, correct, meaningful, and maintainable?
- [ ] **Naming:** Are names clear and descriptive?
- [ ] **Comments:** Do comments explain *why*, not *what*? Are they current?
- [ ] **Documentation:** Are READMEs, API docs, and guides updated as needed?
- [ ] **Style:** Does the code follow the project style guide?
- [ ] **Security:** Are inputs validated, outputs encoded, secrets managed, and
  access controlled?
- [ ] **Performance:** Are there any obvious inefficiencies or scalability
  concerns?
- [ ] **Error handling:** Are failures handled explicitly and gracefully?
- [ ] **Context:** Does this change improve (or at least not degrade) overall code
  health?
