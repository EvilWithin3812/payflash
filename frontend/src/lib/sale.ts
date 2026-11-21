import type { Sale } from '../api/types'

/** Phase computed on the client clock, so badges flip to LIVE exactly when the countdown hits zero. */
export function phaseAt(sale: Sale, now: number): Sale['phase'] {
  if (now < Date.parse(sale.startsAt)) return 'UPCOMING'
  return now < Date.parse(sale.endsAt) ? 'LIVE' : 'ENDED'
}
