# Hans Harness Scenarios

## Scenario H-001: Notice Analysis

### Goal
Verify that the SDK extracts assignment information from notice text or image OCR output.

### Input
- course notice text
- optional OCR text from screenshot

### Expected output
- assignment title
- due date
- submission method
- warning notes

### Validation points
- empty input is rejected
- multiple assignments are separated correctly
- ambiguous due dates are surfaced as warnings

## Scenario H-002: Lecture Note Summary

### Goal
Verify that lecture note content is summarized into study-friendly output.

### Input
- long lecture note text

### Expected output
- short summary
- key concepts
- important points
- review questions

### Validation points
- summary is shorter than source text
- concepts are not duplicated
- review questions are usable for self-test

## Scenario H-003: Study Plan Generation

### Goal
Verify that the SDK builds a realistic study plan from exam data.

### Input
- exam date
- study scope text
- available study hours per day

### Expected output
- daily study plan
- topic priorities
- risk notes

### Validation points
- past dates are rejected
- plan duration matches remaining days
- workload is distributed logically

## Scenario H-004: Team Action Extraction

### Goal
Verify that team project conversation is converted into action items.

### Input
- team chat transcript

### Expected output
- action list
- owner when detectable
- due date when detectable
- unresolved items

### Validation points
- unrelated chat noise is ignored
- missing owner remains nullable
- unresolved decisions are preserved
