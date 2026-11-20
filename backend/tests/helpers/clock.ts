// Freeze Date and Date.now without freezing MongoDB/HTTP I/O or timeout delivery.
// Restore with jest.useRealTimers() in the suite's afterEach hook.
export function freezeDate(now: Date): void {
  jest.useFakeTimers({
    now,
    doNotFake: [
      'hrtime', 'performance', 'nextTick', 'queueMicrotask',
      'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
    ],
  });
}
