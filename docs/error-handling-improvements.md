# Error Handling Improvements

## Overview
Enhanced error structure with typed error codes, field path context, and request-ID correlation support.

## Key Features

### 1. Typed Error Codes (`ValidationErrorCode`)
Machine-readable error codes for categorization:
- `REQUIRED_FIELD` - Missing or empty required field
- `INVALID_FORMAT` - Format validation failure
- `INVALID_RANGE` - Value outside valid range
- `INVALID_LENGTH` - String length constraint violated
- `INVALID_PATTERN` - Pattern matching failure
- `CONSTRAINT_VIOLATION` - Business rule violation
- `TYPE_MISMATCH` - Type mismatch
- `DUPLICATE_VALUE` - Uniqueness constraint violated
- `DEPENDENCY_FAILED` - Related field validation failed
- `INVALID_COMBINATION` - Invalid field combination

### 2. Field Path Context
Error messages now include JSON paths to failing fields:
```scala
// Before
"ID 'x' must be 3-30 alphanumeric characters"

// After
"[root.id] ID 'x' must be 3-30 alphanumeric characters"
"[children[0].minLoss] minLoss (1000) must be less than maxLoss (100)"
```

### 3. Enhanced ErrorDetail Structure
```scala
case class ErrorDetail(
  domain: String,                    // Business domain (e.g., "simulations", "users")
  field: String,                     // JSON path (e.g., "root.children[0].id")
  code: ValidationErrorCode,         // Machine-readable error code
  message: String,                   // Detailed error message
  requestId: Option[String] = None   // Request correlation ID
)
```

**Design Decision**: Removed `reason` field in favor of `code` alone. The typed `ValidationErrorCode` enum provides sufficient categorization without the ambiguity of having two overlapping machine-readable fields.

### 4. Automatic Field Extraction
`ErrorDetail.extractFieldFromMessage()` parses field paths from error messages:
```scala
"[root.id] Invalid" → field = "root.id"
"probability: must be positive" → field = "probability"
"name failed validation" → field = "name"
```

### 5. Error Categorization
`ValidationErrorCode.categorize()` maps free-form messages to typed codes:
```scala
"Name is required" → REQUIRED_FIELD
"minLoss must be less than maxLoss" → INVALID_RANGE
"Too long" → INVALID_LENGTH
"Expert mode requires percentiles" → INVALID_COMBINATION
```

## Implementation Details

### Smart Constructor Field Paths
Smart constructors accept `fieldPrefix` parameter to propagate context:
```scala
RiskLeaf.create(
  id = "test",
  name = "Test",
  // ...
  fieldPrefix = "children[0]"  // Errors will include "[children[0].id]", etc.
)
```

### ValidationUtil Updates
All refinement methods accept `fieldPath` parameter:
```scala
refineName(value, "root.name")
refineProbability(value, "root.probability")
refineNonNegativeLong(value, "root.minLoss")
```

### ErrorDetail Construction
All code uses the full 4-parameter constructor directly:
```scala
ErrorDetail(
  domain = "simulations",            // Configurable per domain
  field = "root.id",                 // Extracted or specified
  code = ValidationErrorCode.INVALID_LENGTH,  // Typed code
  message = "[root.id] ID 'x' must be 3-30 chars",
  requestId = Some("req-123")        // Optional correlation
)
```

### Domain Parameter Support
Error response helpers accept optional `domain` parameter for reusability:
```scala
makeValidationResponse(errors, domain = "users")
makeRepositoryFailureResponse(reason, domain = "analytics")
makeGeneralResponse(domain = "risk-trees")
```

## Testing

### Field Path Tests (RiskLeafSpec)
```scala
suite("Field Path Context in Errors")(
  test("invalid id includes field path") { /* ... */ },
  test("invalid probability includes field path") { /* ... */ },
  test("custom field prefix propagates to errors") { /* ... */ }
)
```

### Results
- **121 tests pass** (37 RiskLeaf + 44 RiskPortfolio + 40 others)
- All field path extraction tests pass
- Error categorization verified
- JSON serialization validated

## Usage Examples

### API Response with Enhanced Errors
```json
{
  "error": {
    "code": 400,
    "message": "Domain validation error",
    "errors": [
      {
        "domain": "simulations",
        "field": "root.id",
        "code": "INVALID_LENGTH",
        "message": "[root.id] ID 'x' must be 3-30 alphanumeric characters",
        "requestId": "req-123"
      },
      {
        "domain": "simulations",
        "field": "root.minLoss",
        "code": "INVALID_RANGE",
        "message": "[root.minLoss] minLoss (1000) must be less than maxLoss (100)"
      }
    ]
  }
}
```

### Client-Side Error Handling
```javascript
fetch('/api/simulations', { /* ... */ })
  .then(res => res.json())
  .then(data => {
    if (data.error) {
      data.error.errors.forEach(err => {
        // Use typed code for specific handling
        if (err.code === 'REQUIRED_FIELD') {
          highlightField(err.field);
        }
        // Show localized message
        showError(i18n.translate(err.code, err.field));
        // Correlate with request
        log.error(err.requestId, err.message);
      });
    }
  });
```

## Benefits

1. **Machine-Readable Errors**: Clients can programmatically handle specific error types
2. **Field Localization**: JSON paths enable precise error highlighting in forms
3. **Request Correlation**: RequestId links errors to specific requests for debugging
4. **Analytics**: Typed codes enable error categorization and monitoring
5. **Internationalization**: Codes can map to localized messages
6. **Debugging**: Field paths + detailed messages aid troubleshooting

## Future Enhancements

1. **Request-ID Middleware**: Auto-generate/extract correlation IDs
2. **Nested Validation**: Handle array indices in field paths (e.g., `children[0].id`)
3. **Error Aggregation**: Group related errors by field path
4. **Validation Context**: Include context (parent object) in error messages
