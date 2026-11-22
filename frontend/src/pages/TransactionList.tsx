import { useState, type ReactNode } from 'react'
import type { Transaction } from '../api/types'
import { Badge, Empty } from '../components/ui'
import { dateTime, money } from '../lib/format'

const label: Record<Transaction['type'], string> = { TOPUP: 'Added money', TRANSFER: 'Transfer', PURCHASE: 'Purchase' }

/** "Sent to Ravi Kumar" / "Received from Asha Rao"; null when there is no other person. */
function headline(t: Transaction): string | null {
  if (t.type !== 'TRANSFER' || !t.counterparty) return null
  return t.direction === 'DEBIT' ? `Sent to ${t.counterparty.fullName}` : `Received from ${t.counterparty.fullName}`
}

export function TransactionList({ items }: { items: Transaction[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (items.length === 0) return <Empty>No transactions yet.</Empty>
  return (
    <ul className="divide-y divide-slate-100">
      {items.map((t) => {
        const credit = t.direction === 'CREDIT'
        const expanded = open === t.reference
        const title = headline(t) ?? (t.note || label[t.type])
        return (
          <li key={t.reference}>
            <button
              type="button"
              onClick={() => setOpen(expanded ? null : t.reference)}
              aria-expanded={expanded}
              aria-controls={`tx-${t.reference}`}
              className="flex w-full items-center gap-4 rounded-lg py-3 text-left transition hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
            >
              <div
                className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-lg font-bold ${
                  credit ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'
                }`}
                aria-hidden
              >
                {credit ? '↓' : '↑'}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-slate-900">{title}</p>
                <p className="text-xs text-slate-500">
                  {dateTime(t.createdAt)} · <Badge tone={t.type === 'PURCHASE' ? 'brand' : 'slate'}>{label[t.type]}</Badge>
                </p>
              </div>
              <p className={`tabular text-sm font-semibold ${credit ? 'text-emerald-600' : 'text-slate-900'}`}>
                {credit ? '+' : '−'}
                {money(t.amount)}
              </p>
              <span className={`text-xs text-slate-400 transition ${expanded ? 'rotate-180' : ''}`} aria-hidden>▾</span>
            </button>
            {expanded && <Details t={t} />}
          </li>
        )
      })}
    </ul>
  )
}

function Details({ t }: { t: Transaction }) {
  const outgoing = t.direction === 'DEBIT'
  const who = t.counterparty
  const rows: [string, ReactNode][] = []
  if (t.type === 'TRANSFER') {
    rows.push([
      outgoing ? 'Sent to' : 'Received from',
      who ? (
        <>
          <span className="font-medium text-slate-900">{who.fullName}</span>
          {who.email && <span className="block break-all text-slate-500">{who.email}</span>}
          {who.deleted && <span className="block text-slate-500">This account has been deleted</span>}
        </>
      ) : (
        'Unknown'
      ),
    ])
  } else if (t.type === 'TOPUP') {
    rows.push(['From', 'Added to your wallet'])
  } else {
    rows.push(['Paid to', 'PayFlash flash sale'])
  }
  rows.push(['Amount', `${outgoing ? '−' : '+'}${money(t.amount)}`])
  if (t.note) rows.push(['Note', t.note])
  rows.push(['Status', t.status.charAt(0) + t.status.slice(1).toLowerCase()])
  rows.push(['Date', new Date(t.createdAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })])
  rows.push(['Reference', <span className="break-all font-mono text-xs">{t.reference}</span>])

  return (
    <dl id={`tx-${t.reference}`} className="mb-3 grid grid-cols-[5.5rem_1fr] gap-x-3 sm:ml-14 sm:grid-cols-[7rem_1fr] sm:gap-x-4 gap-y-2 rounded-lg bg-slate-50 p-4 text-sm">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-slate-500">{k}</dt>
          <dd className="min-w-0 text-slate-700">{v}</dd>
        </div>
      ))}
    </dl>
  )
}
