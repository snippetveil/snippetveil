# Issue tracker: GitHub

Issues for this repo live as GitHub issues in
[`snippetveil/snippetveil`](https://github.com/snippetveil/snippetveil). Use the `gh` CLI for all
operations.

> **Two repositories exist.** This is the **public product** repo. A separate **private planning**
> repo holds the wayfinder map, research and the v1 spec. Never assume which one you are in —
> infer it from this file, not from the directory name.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`.
- **List issues**: `gh issue list --state open --json number,title,body,labels`.
- **Comment**: `gh issue comment <number> --body "..."`
- **Labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`
- **Blocking**: GitHub's native issue dependencies —
  `gh api --method POST repos/snippetveil/snippetveil/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`,
  where `<blocker-db-id>` is the blocker's numeric **database id**
  (`gh api repos/snippetveil/snippetveil/issues/<n> --jq .id`), not its `#number`.
  A ticket is ready when `issue_dependencies_summary.blocked_by` is 0.

Infer the repo from `git remote -v` — `gh` does this automatically when run inside a clone.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.
