import { Link } from 'react-router-dom'
import { useOrders } from '../api/queries'
import type { OrderState } from '../api/types'
import { Badge, Card, Empty, PageTitle, Spinner } from '../components/ui'
import { dateTime, money } from '../lib/format'

const tone: Record<OrderState, 'green' | 'amber' | 'slate' | 'brand'> = {
  PAID: 'green',
  HELD: 'amber',
  PROCESSING: 'brand',
  EXPIRED: 'slate',
}

export function Orders() {
  const { data } = useOrders()
  return (
    <div className="mx-auto max-w-3xl">
      <PageTitle title="My flash-sale orders" />
      <Card>
        {!data && <Spinner />}
        {data?.length === 0 && <Empty>No orders yet. <Link to="/sales" className="font-medium text-brand-600">Browse sales</Link></Empty>}
        <ul className="divide-y divide-slate-100">
          {data?.map((o) => (
            <li key={o.holdToken}>
              <Link to={`/sales/${o.saleId}`} className="flex items-center gap-4 py-3 hover:bg-slate-50">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium">Sale #{o.saleId} · unit #{o.unitNo}</p>
                  <p className="text-xs text-slate-500">{dateTime(o.createdAt)}</p>
                </div>
                <span className="tabular text-sm font-semibold">{money(o.price)}</span>
                <Badge tone={tone[o.status]}>{o.status}</Badge>
              </Link>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}
