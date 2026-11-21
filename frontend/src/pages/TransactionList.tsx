import type { Transaction } from '../api/types'
import { Badge, Empty } from '../components/ui'
import { dateTime, money } from '../lib/format'

const label: Record<Transaction['type'], string> = { TOPUP: 'Added money', TRANSFER: 'Transfer', PURCHASE: 'Purchase' }

export function TransactionList({ items }: { items: Transaction[] }) {
  if (items.length === 0) return <Empty>No transactions yet.</Empty>
  return (
    <ul className="divide-y divide-slate-100">
      {items.map((t) => {
        const credit = t.direction === 'CREDIT'
        return (
          <li key={t.reference} className="flex items-center gap-4 py-3">
            <div
              className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-lg font-bold ${
                credit ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'
              }`}
              aria-hidden
            >
              {credit ? '↓' : '↑'}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-slate-900">{t.note || label[t.type]}</p>
              <p className="text-xs text-slate-500">
                {dateTime(t.createdAt)} · <Badge tone={t.type === 'PURCHASE' ? 'brand' : 'slate'}>{label[t.type]}</Badge>
              </p>
            </div>
            <p className={`tabular text-sm font-semibold ${credit ? 'text-emerald-600' : 'text-slate-900'}`}>
              {credit ? '+' : '−'}
              {money(t.amount)}
            </p>
          </li>
        )
      })}
    </ul>
  )
}
