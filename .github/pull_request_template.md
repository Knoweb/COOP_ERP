<!--
The TITLE of this pull request becomes the commit on main (squash merge), so write it as a
conventional commit: type(scope): what changed
  feat(m1): register an entity      fix(kernel): ...      docs: ...      chore(deps): ...
A check fails when the title does not have that form.
-->

## What this does

<!-- What changed and why, in a few sentences. Name the ticket (S0-07, K-04, M1-05). -->

## Verified

<!-- What you ran, and what happened. Delete the lines that do not apply; do not tick what you did not run. -->

- [ ] `make test`
- [ ] `make test-int`
- [ ] `make check-generated` (after changing an OpenAPI slice or a module dependency)
- [ ] `make up` and `make smoke` (after changing anything the running stack shows)
- [ ] by hand:

## Deviations from the guides

<!-- Anything built differently from 17A, 19A or the module guide, with the reason, and the line
you added to docs/PROGRESS.md. A design that is wrong is a change request in
docs/change-requests/, not a local fix. Write "None" when there are none. -->

## Needs the architect

<!-- A change to kernel.api, to a published api package another module uses, to the build files
or to docs/design. Write "No" when it does not. -->
