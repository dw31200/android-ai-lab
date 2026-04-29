# Hans Harness Runbook

## Purpose
This runbook defines how to use the Hans SDK harness during implementation and QA.

## Usage stages

### Stage 1: Contract validation
- confirm request models are complete
- confirm response models match UI needs
- confirm error mapping is deterministic

### Stage 2: Provider integration validation
- run each scenario with real provider responses
- compare structured output against expected fields
- log request and response ids only

### Stage 3: Regression validation
- rerun all scenarios after prompt changes
- compare output shape and critical field quality

## Logging rules
- do not log API keys
- do not log sensitive user data in release-like runs
- keep fixture data non-sensitive

## Exit criteria
- all four core scenarios return structured output
- all invalid input scenarios return expected exception class
- no scenario requires manual parsing of free-form AI text
