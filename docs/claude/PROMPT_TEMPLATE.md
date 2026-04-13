# Prompt Template (Token-Efficient)

Copy and fill:

```text
Task:
<one clear goal>

Constraints:
- Keep changes minimal.
- Touch only these files: <paths>
- Do not refactor unrelated code.

Required output:
- What changed
- How to run
- Risks/assumptions

Context files (read only these first):
- README.md
- docs/context/architecture.md
- <add 1-3 task-specific files>
```

## Good Example

```text
Task: Update heavy-query experiment to use new attribute mapping.
Constraints: only experiments/scripts/experiment_heavy_query.py and docs/reports/EXPERIMENTS_HEAVY_QUERY.md.
Context files: README.md, docs/context/architecture.md, experiments/scripts/experiment_heavy_query.py.
```
