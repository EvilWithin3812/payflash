import { Link } from 'react-router-dom'
import { useMe } from '../api/queries'
import { money } from '../lib/format'
import { Field } from './ui'

/** The PIN input shown under an amount above the threshold; points to the Account page if no PIN is set yet. */
export function PinField({ value, onChange }: { value: string; onChange: (pin: string) => void }) {
  const me = useMe()
  if (!me.data) return null
  if (!me.data.pinSet) {
    return (
      <p className="rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-900 ring-1 ring-amber-200">
        Payments above {money(me.data.pinThreshold)} need a transaction PIN.{' '}
        <Link to="/account#pin" className="font-semibold underline">Set your PIN</Link> first.
      </p>
    )
  }
  return (
    <Field label="Transaction PIN" type="password" inputMode="numeric" autoComplete="off" pattern="[0-9]{4,6}"
      minLength={4} maxLength={6} required value={value}
      hint={`Needed for payments above ${money(me.data.pinThreshold)}`}
      onChange={(e) => onChange(e.target.value.replace(/\D/g, ''))} />
  )
}

