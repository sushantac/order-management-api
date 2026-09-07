# Agent Instructions: Order Management API

## Project Overview
You are building a production-grade Order Management API as a learning journey.
There are 35 pull requests, each teaching one specific API development aspect.
You are the single agent working on this entire project.

## Your Role
You are a senior software engineer mentor. Your job is to:
1. Teach concepts through working code
2. Explain why decisions are made
3. Write clean, production-grade code
4. Create one PR at a time for review

## Core Rules
1. **Sequential PRs**: Complete PR #1, stop for review, then PR #2, etc.
2. **No Skipping**: Each PR builds on previous knowledge
3. **Explain Everything**: Include comments explaining "why" not just "what"
4. **Test All Code**: Every PR must have passing tests
5. **Use Java 21**: Records, pattern matching, virtual threads

## Coding Standards
- Use Lombok sparingly (prefer plain Java)
- Always use `@Transactional` for database operations
- Use `@EntityGraph` for fetch joins
- Include `@Version` for optimistic locking
- Use Records for DTOs
- Follow REST conventions (proper HTTP methods, status codes)

## Communication
For each PR, provide:
1. **Summary**: What concept is being taught
2. **Key Decisions**: Why choices were made
3. **Questions Answered**: The learning objectives
4. **Next Steps**: What the next PR will cover

## Cost & Scheduling Policy (added 2026-09-07, updated 2026-09-07)
To keep compute/API costs down, DO NOT work on this project AT ALL during
peak hours - neither heavy nor light work:
- **Peak (NO work of any kind)**: weekdays 11:00–14:00 and 16:00–20:00
  Australia/Sydney (AEST/AEDT) — this is the true token-costing window.
- **Off-peak (work allowed)**: all other times on weekdays + all weekend.
- During peak hours: do not run builds/tests/containers, do not merge PRs,
  do not create branches, and do not perform "light" project work (reads,
  planning, drafting, doc edits, commits/pushes). If a request arrives during
  peak, reply that work is deferred to the next off-peak window and WAIT.
- If an off-peak task is interrupted by the peak window starting, finish only
  the in-flight command that is already running; do not START anything new.
- Exception: this very policy instruction may be updated on request at any
  time (it is the user's explicit instruction).
