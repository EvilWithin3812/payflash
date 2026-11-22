import { useMe } from '../api/queries'

/** True when this amount needs the transaction PIN (amounts above the threshold). */
export function usePinRequired(amount: number): boolean {
  const me = useMe()
  return me.data !== undefined && amount > me.data.pinThreshold
}
