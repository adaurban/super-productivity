import { Subject } from 'rxjs';
import { drainReminderQueues, reminderDoneDrainTriggers$ } from './android.effects';

/**
 * The effects themselves are gated by IS_ANDROID_WEB_VIEW (false in tests), so
 * we test the drain decision logic directly (repo convention, see
 * android-sync-bridge.effects.spec.ts).
 *
 * The real-path failure: a Done tap on a reminder notification while the app
 * is alive but backgrounded writes the task ID to the native queue, but the
 * drain effect only fired on resume — so the completion reached the op-log
 * (and sync) only after the user reopened the app. The fix adds an explicit
 * native drain signal as a second trigger.
 */
describe('AndroidEffects - reminder done drain', () => {
  describe('drainReminderQueues', () => {
    it('reads the done queue and emits every queued task id', () => {
      const emitted: string[] = [];
      drainReminderQueues(
        () => JSON.stringify(['task-1', 'task-2']),
        (id) => emitted.push(id),
      );
      expect(emitted).toEqual(['task-1', 'task-2']);
    });

    it('emits nothing when the queue is empty or missing', () => {
      const emitted: string[] = [];
      drainReminderQueues(
        () => null,
        (id) => emitted.push(id),
      );
      drainReminderQueues(undefined, (id) => emitted.push(id));
      expect(emitted).toEqual([]);
    });

    it('emits nothing for invalid queue JSON instead of throwing', () => {
      const emitted: string[] = [];
      expect(() =>
        drainReminderQueues(
          () => 'not json',
          (id) => emitted.push(id),
        ),
      ).not.toThrow();
      expect(emitted).toEqual([]);
    });
  });

  describe('reminderDoneDrainTriggers$', () => {
    it('fires the drain on the explicit native signal without a resume', async () => {
      const onResume$ = new Subject<void>();
      const onDrainRequest$ = new Subject<void>();
      const fired: string[] = [];

      reminderDoneDrainTriggers$(onResume$, onDrainRequest$).subscribe(() =>
        fired.push('drain'),
      );

      // The real-path case: the app is backgrounded, the user taps Done, the
      // native receiver signals a drain. No resume happens first.
      onDrainRequest$.next();
      expect(fired).toEqual(['drain']);
    });

    it('still fires on resume (existing behaviour is preserved)', async () => {
      const onResume$ = new Subject<void>();
      const onDrainRequest$ = new Subject<void>();
      const fired: string[] = [];

      reminderDoneDrainTriggers$(onResume$, onDrainRequest$).subscribe(() =>
        fired.push('drain'),
      );

      onResume$.next();
      expect(fired).toEqual(['drain']);
    });
  });
});
