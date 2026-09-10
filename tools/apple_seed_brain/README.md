# Apple Seed Brain — Local v1

A first offline problem-solving tool for Apple Seed. It uses only Python's standard library and local JSON files; it does not call an AI API.

## Run on Windows

1. Install Python 3.11+ if Python is not already installed.
2. Double-click `start_brain.bat`.
3. Open `http://127.0.0.1:8765` in the browser.

## What v1 does

- Accepts model, panic/log, boot current, symptoms, measurements and previous repair attempts.
- Ranks Apple Seed knowledge and confirmed local repair cases by evidence overlap.
- Produces hypotheses, evidence hits and a next measurement step.
- Saves technician-confirmed cases to `data/cases.json`.
- Keeps all data on the local computer.

This is deliberately the foundation, not a claim that a rule engine is equivalent to a large language model. The next layer can add a local model without changing the case-memory interface.
