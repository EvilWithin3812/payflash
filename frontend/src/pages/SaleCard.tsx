import { Link } from 'react-router-dom'
import type { Sale } from '../api/types'
import { Badge } from '../components/ui'
import { duration, money } from '../lib/format'
import { useLiveStock, useNow } from '../lib/hooks'
import { phaseAt } from '../lib/sale'

export function StockBar({ remaining, total }: { remaining: number; total: number }) {
  const pct = total > 0 ? (remaining / total) * 100 : 0
  const color = pct > 50 ? 'bg-emerald-500' : pct > 15 ? 'bg-amber-500' : 'bg-rose-500'
  return (
    <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100" role="progressbar" aria-valuenow={remaining} aria-valuemax={total}>
      <div className={`h-full rounded-full transition-all duration-500 ${color}`} style={{ width: `${pct}%` }} />
    </div>
  )
}

export function SaleCard({ sale }: { sale: Sale }) {
  const now = useNow()
  const remaining = useLiveStock(sale.id, sale.remainingUnits) ?? sale.remainingUnits
  const phase = phaseAt(sale, now)
  const soldOut = phase === 'LIVE' && remaining === 0

  return (
    <Link to={`/sales/${sale.id}`} className="group block rounded-2xl bg-white p-5 shadow-sm ring-1 ring-slate-200 transition hover:shadow-md hover:ring-brand-500">
      <div className="flex items-start justify-between gap-3">
        <h3 className="font-semibold text-slate-900 group-hover:text-brand-700">{sale.name}</h3>
        {soldOut ? <Badge tone="red">SOLD OUT</Badge> : phase === 'LIVE' ? <Badge tone="green">LIVE</Badge> : phase === 'UPCOMING' ? <Badge tone="amber">SOON</Badge> : <Badge>ENDED</Badge>}
      </div>
      {sale.description && <p className="mt-1 line-clamp-2 text-sm text-slate-500">{sale.description}</p>}
      <p className="tabular mt-4 text-2xl font-bold">{money(sale.price)}</p>
      <div className="mt-4 space-y-1.5">
        <StockBar remaining={remaining} total={sale.totalUnits} />
        <p className="tabular flex justify-between text-xs text-slate-500">
          <span>{remaining} of {sale.totalUnits} left</span>
          <span>
            {phase === 'UPCOMING' && `starts in ${duration(Date.parse(sale.startsAt) - now)}`}
            {phase === 'LIVE' && `ends in ${duration(Date.parse(sale.endsAt) - now)}`}
          </span>
        </p>
      </div>
    </Link>
  )
}
