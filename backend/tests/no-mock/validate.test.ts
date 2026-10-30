import { ValidationError } from '../../src/common/errors';
import {
  type Parser,
  fields,
  isoDate,
  numberInRange,
  objectId,
  oneOf,
  oneOfMany,
  optionalText,
  parseInput,
  text,
} from '../../src/common/middleware/validate';

const COLORS = ['red', 'green', 'blue'] as const;
const OBJECT_ID = '507f1f77bcf86cd799439011';

function f(entries: Record<string, unknown>) {
  return fields(entries);
}

// expect.objectContaining is typed any; give toThrow a typed matcher for the expected details
function errorWithDetails(details: string[]): Error {
  const matcher: Error = expect.objectContaining({ details });
  return matcher;
}

// Interface: parseInput
describe('Unmocked: parseInput', () => {
  // Input: a parser and some data
  // Expected behavior: the parser's result is returned; its errors propagate
  // Expected output: parser output / ValidationError
  test('Delegates to the parser', () => {
    const parser: Parser<number> = (data) => {
      if (typeof data !== 'number') throw new ValidationError(['not a number']);
      return data * 2;
    };

    expect(parseInput(parser, 21)).toBe(42);
    expect(() => parseInput(parser, 'x')).toThrow(ValidationError);
  });
});

// Interface: fields
describe('Unmocked: fields', () => {
  // Input: a plain object
  // Expected output: a map of its entries
  test('Converts an object to a map', () => {
    expect(fields({ a: 1 }).get('a')).toBe(1);
  });

  // Input: non-object / null / array, with and without a custom name
  // Expected behavior: ValidationError mentioning the name
  test.each([['string'], [null], [[1, 2]], [undefined], [5]])('Rejects %j', (data) => {
    expect(() => fields(data)).toThrow(ValidationError);
  });

  // Input: null with the name "params", then null with no name
  // Expected behavior: ValidationError thrown
  // Expected output: details ["params must be an object"]; details ["body must be an object"]
  test('Error message uses the supplied name', () => {
    expect(() => fields(null, 'params')).toThrow(
      errorWithDetails(['params must be an object']),
    );
    expect(() => fields(null)).toThrow(errorWithDetails(['body must be an object']));
  });
});

// Interface: text
describe('Unmocked: text', () => {
  // Input: string with surrounding whitespace
  // Expected output: trimmed string
  test('Trims valid text', () => {
    expect(text(f({ k: '  hi  ' }), 'k', 5)).toBe('hi');
  });

  // Input: missing / non-string / blank / too long (measured after trimming)
  // Expected behavior: ValidationError
  test.each([[{}], [{ k: 5 }], [{ k: '   ' }], [{ k: 'abcdef' }], [{ k: '  abcdef  ' }]])('Rejects %j', (body) => {
    expect(() => text(f(body), 'k', 5)).toThrow(ValidationError);
  });

  // Input: text whose trimmed length equals the limit
  // Expected behavior: accepted
  test('Accepts text exactly at the limit', () => {
    expect(text(f({ k: ' abcde ' }), 'k', 5)).toBe('abcde');
  });
});

// Interface: optionalText
describe('Unmocked: optionalText', () => {
  // Input: missing key / null
  // Expected output: empty string
  test.each([[{}], [{ k: null }]])('Defaults %j to an empty string', (body) => {
    expect(optionalText(f(body), 'k', 5)).toBe('');
  });

  // Input: string with whitespace, empty string
  // Expected output: trimmed string
  test('Trims provided text', () => {
    expect(optionalText(f({ k: '  hi ' }), 'k', 5)).toBe('hi');
    expect(optionalText(f({ k: '' }), 'k', 5)).toBe('');
  });

  // Input: non-string / over the limit
  // Expected behavior: ValidationError
  test.each([[{ k: 5 }], [{ k: 'abcdef' }]])('Rejects %j', (body) => {
    expect(() => optionalText(f(body), 'k', 5)).toThrow(ValidationError);
  });
});

// Interface: numberInRange
describe('Unmocked: numberInRange', () => {
  // Input: numbers inside the range, including both bounds
  // Expected output: the number
  test.each([[-10], [0], [10]])('Accepts %p', (n) => {
    expect(numberInRange(f({ k: n }), 'k', -10, 10)).toBe(n);
  });

  // Input: missing / string / NaN / Infinity / below min / above max
  // Expected behavior: ValidationError
  test.each([[{}], [{ k: '5' }], [{ k: Number.NaN }], [{ k: Infinity }], [{ k: -11 }], [{ k: 11 }]])(
    'Rejects %j',
    (body) => {
      expect(() => numberInRange(f(body), 'k', -10, 10)).toThrow(ValidationError);
    },
  );
});

// Interface: oneOf
describe('Unmocked: oneOf', () => {
  // Input: an allowed value
  // Expected output: the value
  test('Accepts allowed values', () => {
    expect(oneOf(f({ k: 'green' }), 'k', COLORS)).toBe('green');
  });

  // Input: missing / unlisted / wrong type
  // Expected behavior: ValidationError listing the allowed values
  test.each([[{}], [{ k: 'pink' }], [{ k: 1 }]])('Rejects %j', (body) => {
    expect(() => oneOf(f(body), 'k', COLORS)).toThrow(
      errorWithDetails(['k must be one of red, green, blue']),
    );
  });
});

// Interface: oneOfMany
describe('Unmocked: oneOfMany', () => {
  // Input: missing key
  // Expected output: empty array
  test('Defaults to an empty array', () => {
    expect(oneOfMany(f({}), 'k', COLORS, 2)).toEqual([]);
  });

  // Input: array of allowed values within the limit
  // Expected output: the same values
  test('Accepts allowed values', () => {
    expect(oneOfMany(f({ k: ['red', 'blue'] }), 'k', COLORS, 2)).toEqual(['red', 'blue']);
    expect(oneOfMany(f({ k: [] }), 'k', COLORS, 2)).toEqual([]);
  });

  // Input: not an array / too many items / an invalid item
  // Expected behavior: ValidationError
  test.each([[{ k: 'red' }], [{ k: null }], [{ k: ['red', 'green', 'blue'] }], [{ k: ['red', 'pink'] }]])(
    'Rejects %j',
    (body) => {
      expect(() => oneOfMany(f(body), 'k', COLORS, 2)).toThrow(ValidationError);
    },
  );
});

// Interface: isoDate
describe('Unmocked: isoDate', () => {
  // Input: ISO-8601 date-time strings
  // Expected output: the unchanged string
  test.each([['2026-11-02T10:30:00Z'], ['2026-11-02T10:30:00.000+00:00'], ['2026-11-02T10:30']])(
    'Accepts %s',
    (value) => {
      expect(isoDate(f({ k: value }), 'k')).toBe(value);
    },
  );

  // Input: missing / non-string / wrong format / unparseable date
  // Expected behavior: ValidationError
  test.each([[{}], [{ k: 20261102 }], [{ k: '2026-11-02' }], [{ k: 'tomorrow' }], [{ k: '2026-13-45T99:99' }]])(
    'Rejects %j',
    (body) => {
      expect(() => isoDate(f(body), 'k')).toThrow(ValidationError);
    },
  );
});

// Interface: objectId
describe('Unmocked: objectId', () => {
  // Input: 24-hex strings in either case
  // Expected output: the unchanged string
  test.each([[OBJECT_ID], [OBJECT_ID.toUpperCase()]])('Accepts %s', (value) => {
    expect(objectId(f({ k: value }), 'k')).toBe(value);
  });

  // Input: missing / non-string / wrong length / non-hex
  // Expected behavior: ValidationError
  test.each([[{}], [{ k: 5 }], [{ k: '507f1f77' }], [{ k: 'z'.repeat(24) }]])('Rejects %j', (body) => {
    expect(() => objectId(f(body), 'k')).toThrow(ValidationError);
  });
});
