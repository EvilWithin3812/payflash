import { Link } from 'react-router-dom'
import { useSales, useTransactions, useWallet } from '../api/queries'
import { useSession } from '../auth/session'
import { Alert, Badge, Card, Spinner } from '../components/ui'
import { money } from '../lib/format'
import { TransactionList } from './TransactionList'

export function Dashboard() {
  const session = useSession()
  const wallet = useWallet()
  const recent = useTransactions(0, 5)
  const sales = useSales()
  const live = sales.data?.filter((s) => s.phase === 'LIVE') ?? []

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="space-y-6 lg:col-span-2">
        <div className="rounded-2xl bg-gradient-to-br from-brand-600 to-violet-600 p-6 text-white shadow-lg">
          <p className="text-sm text-white/80">Hi {session?.email}, your balance is</p>
          <p className="tabular mt-2 text-4xl font-bold tracking-tight">
            {wallet.data ? money(wallet.data.balance) : <Spinner className="h-8 w-8" />}
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <Link to="/add-money" className="rounded-lg bg-white px-4 py-2 text-sm font-semibold text-brand-700 hover:bg-brand-50">
              + Add money
            </Link>
            <Link to="/send" className="rounded-lg bg-white/15 px-4 py-2 text-sm font-semibold text-white ring-1 ring-white/30 hover:bg-white/25">
              Send money
            </Link>
          </div>
        </div>
        {wallet.isError && <Alert>Could not load your wallet.</Alert>}

        <Card>
          <div className="mb-2 flex items-center justify-between">
            <h2 className="font-semibold">Recent activity</h2>
            <Link to="/transactions" className="text-sm font-medium text-brand-600 hover:text-brand-700">See all</Link>
          </div>
          {recent.data ? <TransactionList items={recent.data.content} /> : <Spinner />}
        </Card>
      </div>

      <Card className="h-fit">
        <h2 className="font-semibold">Flash sales</h2>
        <p className="mt-1 text-sm text-slate-500">Limited stock, first come first served.</p>
        <ul className="mt-4 space-y-3">
          {live.slice(0, 4).map((s) => (
            <li key={s.id}>
              <Link to={`/sales/${s.id}`} className="block rounded-xl p-3 ring-1 ring-slate-200 hover:ring-brand-500">
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium">{s.name}</span>
                  <Badge tone="green">LIVE</Badge>
                </div>
                <p className="tabular mt-1 text-sm text-slate-500">{money(s.price)} · {s.remainingUnits} left</p>
              </Link>
            </li>
          ))}
          {live.length === 0 && <li className="text-sm text-slate-500">No live sales right now.</li>}
        </ul>
        <Link to="/sales" className="mt-4 inline-block text-sm font-medium text-brand-600">All sales →</Link>
      </Card>
    </div>
  )
}
