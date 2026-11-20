import { tuning } from '../config';

// Coalesce bursts across all sharing devices in a meetup. A trailing event
// ensures the most recent point is fetched even when movement stops mid-burst.
export class TripUpdates {
  private readonly pending = new Map<string, { dirty: boolean; timer: ReturnType<typeof setTimeout> }>();
  constructor(private readonly emit: (meetupId: string) => void) {}

  position(meetupId: string): void {
    const slot = this.pending.get(meetupId);
    if (slot) { slot.dirty = true; return; }
    this.emit(meetupId);
    this.schedule(meetupId);
  }

  state(meetupId: string): void {
    const slot = this.pending.get(meetupId);
    if (slot) clearTimeout(slot.timer);
    this.pending.delete(meetupId);
    this.emit(meetupId); // Pause, resume and arrival must not wait for movement throttling.
  }

  private schedule(meetupId: string): void {
    const slot = { dirty: false, timer: setTimeout(() => {
      this.pending.delete(meetupId);
      if (slot.dirty) { this.emit(meetupId); this.schedule(meetupId); }
    }, tuning.tripUpdateIntervalMs) };
    slot.timer.unref();
    this.pending.set(meetupId, slot);
  }
}
