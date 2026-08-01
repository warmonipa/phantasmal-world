# Project Workflow

This is a single-maintainer personal project. Do not introduce pull-request or
multi-reviewer workflows unless the user explicitly asks for a PR.

For review/fix/release requests, use this workflow:

1. Review and fix the scoped changes.
2. Run the relevant local tests and production build.
3. Update documentation when behavior or maintenance boundaries change.
4. Commit and push the current branch when requested.
5. Manually dispatch the `Deploy` GitHub Actions workflow for the current
   branch when deployment is requested or clearly part of the established
   handoff.
6. Monitor both the Deploy job and the resulting Pages publication to
   completion before reporting deployment success.

The `Tests` workflow is validation only. A successful Tests run is not a
deployment. Do not create a PR merely to run tests; local validation is the
primary pre-push check for this project.

Always prefix shell commands with `rtk`, as required by
`/Users/wangzhen/.codex/RTK.md`.
