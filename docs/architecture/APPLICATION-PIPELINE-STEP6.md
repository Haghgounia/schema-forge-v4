# Application Pipeline - Step 6

This step makes the complete offline pipeline testable without invoking `main` or using `System.exit`.

## Added

- `application.SchemaGenerationService`
- `application.GenerationOutput`
- End-to-end Word -> canonical model -> validation -> JSON + PostgreSQL SQL integration test

## Compatibility

- Command-line forms are unchanged.
- Oracle remains the default platform.
- Output file names remain `<word-file-base>.json` and `<word-file-base>.sql`.
- No new framework or architectural layer was introduced beyond the existing `application` package.
