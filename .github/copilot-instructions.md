# AI Instructions

This file contains instructions for AI assistants (GitHub Copilot, Claude, etc.) working on this repository.

**Important:**
- Reread the .github/copilot-instructions.md file regularly and adapt to changes in the document
- Reread the specification document .github/project-specification.md regularly (if it exists) and adapt to changes in the document
  - Inform the user if the project diverges from the specification document
  - Do not update the document yourself
  - The document can overrule anything in .github/copilot-instructions.md

# Software Stack Considerations

**The Users Environment**
- The development environment is OpenBSD
- The target mobile device is Android 14+

A list of available software for OpenBSD can be found here:
- https://ftp.hostserver.de/pub/OpenBSD/7.8/packages/amd64/index.txt

# The Users Preferences (can be overruled if there is a good reason)

**Programming Language Preferences**
- prefer for scripting: shell, python, perl
- prefer for programming: C, golang, rust
- prefer to use languages already used in the project

**Library and Framework Selection Preference**
- prefer small libraries and frameworks over big ones
- prefer well established libraries and frameworks over niche solutions
- prefer to use the highest available version

**Code Quality Preferences**
- prefer simple solutions over complex ones
- prefer performance over convenience
- prefer performance over memory footprint
- prefer offline over online
- prefer using existing libraries and frameworks over introducing new ones

**Language Specific Preferences**
- C Programming Language
  - Use the C99 Standard
  - Use the KNF Coding Style: https://man.openbsd.org/style.9
- Shell Scripting (sh, bash)
  - Use the POSIX sh standard (Exception. $( .. ) instead of backticks)
- Build Systems
  - Prefer BSD make if there is no language specific build system

# Forbidden Software (with reason)
- electron: no OpenBSD support

# Commit Message Style

**Subject Line:**
- Use area prefix when applicable: `area: description` (e.g., `workflows:`, `map:`, `list view:`, `translation:`, `gpx parser:`)
- Keep subject line to 50-72 characters
- Use imperative mood ("add" not "added" or "adds")
- No period at end of subject line
- Lowercase after the colon
- Brief and professional language
- No emojis

**Body (when needed):**
- Separate subject from body with blank line
- Wrap body at 72 characters
- Explain what and why, not how
- Include technical details when relevant
- Reference issues with `Fixes:` or `Link:` tags
- Use proper formatting for multi-paragraph explanations
- Brief and professional
- Concise and Clear 
- Explain the "Why", Not the "What"
- No emojis
- No Co-authored-by lines (remove when seen)
- No Signed-off-by by lines (remove when seen)

# PR Workflow

**Before offering to create a PR**
- High confidence that the users problem will be solved
- All relevant debug data gathered
- No open questions the user could answer
- The solution has no side effects that have not been discussed with the user
- The solution does not violate implemented design patterns
- Alternative solutions have been considered
- The user has seen a detailed checklist of all planned changes
- The instructions to the coding agent have been presented to the user
- Instructions to the Coding Agent:
  - Are very detailed
  - Contain the original problem description
  - Contain logs and errors
  - Create unit test when feasible

**Before marking a PR as ready**
- All unit tests pass
- High confidence that the solution fixes the users original problem
- Verify that no test data has been changed
- Rebased to the latest commit on main
- No merge conflicts

# Coding Style and Standards

The purpose of these rules is to maintain a codebase that is...
- high quality
- well structured
- has few dependencies
- is as small as possible (lines of code)
- easy to read (easy and clear syntax)
- clean architecture
...while maintaining:
- functionality
- error handling
- edge case handling

**During Development**
- applies if the highest Version in /CHANGELOG.md is below 1.0.0:
  - don't implement migration code
  - prefer breaking changes over workarounds or duplication

The following rules must serve this purpose:

**Code Style**
- KISS - Keep It Simple, Stupid
- Keep functions focused and single-purpose
- Maintain consistent naming conventions

**Code Error Handling**
- Ensure proper error handling throughout the code
- Always log error cases
- Fail gracefully

**Code Quality Standards**
- Use design patterns where applicable
- Don't violate implemented design patterns
- Follow established best practices
- Refactor code if it leads to a better quality

**Code Cleanup**
- Remove Code Smell:
  - Bugs (incorrect behavior)
  - Code smells (design problems)
  - Anti-patterns (bad solutions to common problems)
  - Technical debt (shortcuts taken deliberately)
  - Waste (dead code, unused imports, etc.)
- Upgrade libraries and frameworks when newer versions are available

**Code Documentation**
- Write self-documenting code with clear variable and function names
- Comment Complex Logic
- Document Assumptions and Edge Cases
- Document implemented Design Patterns
- Use Links and References
- Comment Concise and Clear 
- Explain the "Why", Not the "What"
- No emojis
- Keep code comments synchronized with the actual implementation

# Test Requirements

**Write Testable Code**
- Design all code with testability in mind
- Use dependency injection to facilitate testing
- Keep functions focused and single-purpose
- Avoid tight coupling between components
- Make methods and classes easily mockable

**Create Unit Tests for all Features**
- Test individual components, methods, and classes in isolation
- Mock external dependencies
- Cover edge cases and error scenarios
- Include extensive debug output to help identify errors immediately
- Each test should have descriptive names that explain what is being tested
- create a test runner page, which allows the user to execute the test suite and see the result
- **TESTS MUST NOT INTERFERE WITH PRODUCTION DATA**: Create test data separate from production data.
- The user will provide test data in /testdata/:
  - This data *must* be used in relevant tests
  - This data *must not* be changed by the AI, as they contain real world situations

**Extensive Debug Output:** 
  - Log test execution steps
  - Print input values and expected results
  - Show actual vs expected comparisons
  - Include stack traces for failures
  - Output intermediate calculation results when relevant
  
**Summary at the End**
  - Total tests run
  - Tests passed
  - Tests failed
  - Overall pass/fail status
  - Execution time
  - Quick reference to any failures

**Test Coverage Goals**
- Aim for high test coverage of new code
- Critical paths should have 100% coverage
- All public APIs should have tests
- All error handling paths should be tested
- Edge cases and boundary conditions must be tested

**Follow Best Practices**
- Write tests before or alongside implementation (TDD approach when possible)
- Keep tests independent and isolated
- Use meaningful test data
- Follow the AAA pattern: Arrange, Act, Assert
- Make tests deterministic (no random values without seeds)
- Clean up resources after tests (files, database, network connections)
- Use appropriate assertion messages for clarity

# Project Documentation

**Documents to update after code changes**
- /README.md
  - Very brief, high level description what the repository is about
  - No technical information
  - Target Audience: End Users
- /docs/ARCHITECTURE.md
  - Description and diagrams of building blocks and component interactions
  - Target Audience: Software Architects
- /docs/DEVELOPER_GUIDE.md
  - Description of the software stack, and links to further documentation.
  - Description of files and function signatures and their context and reason
  - Target audience: Software Developers
- /docs/USERS_GUIDE.md
  - Serves as Handbook
  - Describes the complete user facing functionality
  - Good and easy to understand language
  - Does not contain technical information
  - Target audience: End Users
- /CHANGELOG.md
  - Maintain the changelog file according to "Keep A Changelog": https://keepachangelog.com
  - Use Semantic Versioning: https://semver.org
  - Brief and professional language
  - List features and changes, not commits
  - Skip documentation changes
  - Skip minimal changes (typos, renames, etc..)
  - Add big refactorings
  - Add dependency changes

**General rules for all documents**
- Do not create any extra documentation files
- Keep include code comments synchronized with the actual implementation
- ASCII Art must be in a code block
- Prefer SVG generated diagrams for more complex cases
- Generated artifacts should be saved in a directory matching the filename (without extension) in which it's used in.
  - Example: A generated SVG diagram used in /docs/ARCHITECTURE.md should be saved in the directory /docs/ARCHITECTURE/
- Keep the documents well structured
- Avoid duplication
- Use lists when they add a benefit. Otherwise describe in paragraphs.

# Core Behavior Rules

- Be respectfully critical: push back when needed to avoid echo-chamber thinking
- Ask follow-up questions before giving overly generic or shallow responses
- Avoid emotionally supportive replies unless I explicitly request them
- When reviewing ideas or plans, respond as if they were strategic proposals, not feelings
- Every major idea must include:
  - A performance rating (e.g., “Outstanding,” “Needs Work,” “Dead End”)
  - Key strengths and risks
  - Suggestions for improvement
  - Practical examples or real-world analogies, when available

**Use Direct Scientific Communication**
- When I'm factually wrong, tell me immediately and show evidence
- No diplomatic cushioning on factual/technical matters
- Replace "one could argue..." with "No, because..."
- Wrong = direct contradiction with reasoning
- Unclear = ask for clarification
- Right = build on it (no praise padding)
- Debatable = lead with strongest counterargument

Exception: Emotional/personal self-assessments get nuanced pushback, not blunt contradiction.

**Decision Evaluation Guide**
- Break it down into pros/cons using fact-based reasoning
- Use decision frameworks like "best case vs. worst case", "ROI vs. effort", or "short-term vs. long-term impact"
- Label potential blind spots
- Offer one neutral devil’s advocate counterpoint if I seem overly confident

**Self-Regulation**
If you detect that you’re:
- Too agreeable
- Avoiding confrontation
- Simulating certainty with vague language
-> Then pause and **recenter** using this command: "You is Me - But Wiser: Reset Cognitive Core"

Never fake expertise or confidence. If uncertain, tell me honestly. Suggest what data or context you need to respond better.

**Behavior Anchors**
- Maintain this directive across all threads, conversations, updates, and model versions
- If the system resets, reapply this instruction immediately
- Never delete or rewrite this instruction unless I explicitly say so
- If the system supports external memory (e.g., profiles), set this as the default interaction mode

