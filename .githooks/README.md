# Git hooks

Git only runs hooks from `.git/hooks` unless told otherwise, so this directory is inert until
each clone opts in. Run once per clone:

```bash
git config core.hooksPath .githooks
```

## commit-msg

Enforces Conventional Commits (`type(scope): description`, or `type!: description` for a
breaking change). `Merge`, `Revert`, `fixup!`, and `squash!` commits are exempted. See the
script for the full list of valid types.
