<!-- Thanks for contributing. Keep the diff to one logical change. -->

## What this changes

<!-- One paragraph. If it fixes an issue, write "Fixes #123". -->

## Why

<!-- What problem does this solve? Link the issue where the design was agreed, if there is one. -->

## How it was tested

<!-- Name the tests you added or ran. "mvn clean verify passes" alone is not enough
     for a behavioural change. -->

## Checklist

- [ ] `mvn clean verify` passes locally on JDK 21
- [ ] **No new runtime dependency**, the framework still builds and ships with the JDK alone
- [ ] No dynamic proxy, bytecode generation, annotation processor or classpath scanning
- [ ] **No lambda expressions**, anonymous inner classes and explicit loops, matching the surrounding code
- [ ] Tests added or updated for the behaviour that changed
- [ ] Tests pass without an API key or a network connection
- [ ] Public API additions carry javadoc
- [ ] Any new limit is documented plainly

## Notes for the reviewer

<!-- Anything subtle: a trade-off you made, a case you deliberately left out. -->
