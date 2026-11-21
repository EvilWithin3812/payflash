import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, errorCode, errorMessage } from '../api/client'
import { keys, useOrders, useSale, useWallet } from '../api/queries'
import type { SaleOrder } from '../api/types'
import { Alert, Badge, Button, Card, Spinner } from '../components/ui'
import { duration, money } from '../lib/format'
import { useLiveStock, useNow } from '../lib/hooks'
import { phaseAt } from '../lib/sale'
import { StockBar } from './SaleCard'

export function SaleDetail() {
  const saleId = Number(useParams().saleId)
  const queryClient = useQueryClient()
  const now = useNow(250)
  const sale = useSale(saleId)
  const wallet = useWallet()
  const myOrders = useOrders()
  const remaining = useLiveStock(saleId, sale.data?.remainingUnits)

  // The reservation being followed on this page: one just made, or an existing live one for this sale.
  const [token, setToken] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (token || !myOrders.data) return
    const existing = myOrders.data.find((o) => o.saleId === saleId && o.status !== 'EXPIRED')
    if (existing) setToken(existing.holdToken)
  }, [myOrders.data, saleId, token])

  // Poll quickly while the reservation is still in the Kafka queue (PROCESSING), then stop.
  const order = useQuery({
    queryKey: keys.order(token ?? ''),
    enabled: !!token,
    queryFn: async () => (await api.get<SaleOrder>(`/sales/orders/${token}`)).data,
    refetchInterval: (q) => (q.state.data?.status === 'PROCESSING' ? 600 : false),
  })

  const reserve = useMutation({
    mutationFn: async () => (await api.post<SaleOrder>(`/sales/${saleId}/reserve`)).data,
    onMutate: () => setError(null),
    onSuccess: (o) => {
      queryClient.setQueryData(keys.order(o.holdToken), o)
      setToken(o.holdToken)
    },
    onError: (e) => {
      if (errorCode(e) === 'ALREADY_RESERVED') queryClient.invalidateQueries({ queryKey: keys.orders })
      setError(errorMessage(e))
    },
  })

  const pay = useMutation({
    mutationFn: async () => (await api.post<SaleOrder>(`/sales/orders/${token}/pay`)).data,
    onMutate: () => setError(null),
    onSuccess: (o) => {
      queryClient.setQueryData(keys.order(o.holdToken), o)
      queryClient.invalidateQueries({ queryKey: keys.wallet })
      queryClient.invalidateQueries({ queryKey: keys.orders })
      queryClient.invalidateQueries({ queryKey: ['transactions'] })
    },
    onError: (e) => {
      if (errorCode(e) === 'HOLD_EXPIRED') order.refetch()
      setError(errorMessage(e))
    },
  })

  if (sale.isError) return <Alert>Sale not found.</Alert>
  if (!sale.data) return <Spinner />
  const s = sale.data
  const phase = phaseAt(s, now)
  const left = remaining ?? s.remainingUnits
  const o = order.data
  const holdLeft = o?.expiresAt ? Date.parse(o.expiresAt) - now : null
  const holdExpired = o?.status === 'EXPIRED' || (o?.status === 'HELD' && holdLeft !== null && holdLeft <= 0)

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link to="/sales" className="text-sm font-medium text-brand-600">← All sales</Link>
      <Card className="overflow-hidden p-0">
        <div className="bg-gradient-to-br from-slate-900 to-brand-700 p-8 text-white">
          <div className="flex items-center gap-2">
            {phase === 'LIVE' && left > 0 && <Badge tone="green">LIVE</Badge>}
            {phase === 'LIVE' && left === 0 && <Badge tone="red">SOLD OUT</Badge>}
            {phase === 'UPCOMING' && <Badge tone="amber">STARTS SOON</Badge>}
            {phase === 'ENDED' && <Badge>ENDED</Badge>}
          </div>
          <h1 className="mt-3 text-3xl font-bold tracking-tight">{s.name}</h1>
          {s.description && <p className="mt-2 text-white/80">{s.description}</p>}
          <p className="tabular mt-6 text-4xl font-bold">{money(s.price)}</p>
        </div>

        <div className="space-y-6 p-8">
          <div>
            <div className="mb-2 flex items-baseline justify-between">
              <p className="tabular text-5xl font-bold tracking-tight" aria-live="polite">{left}</p>
              <p className="text-sm text-slate-500">of {s.totalUnits} units left · updates live</p>
            </div>
            <StockBar remaining={left} total={s.totalUnits} />
          </div>

          {phase === 'UPCOMING' && (
            <div className="rounded-xl bg-amber-50 p-4 text-center">
              <p className="text-sm text-amber-800">Sale starts in</p>
              <p className="tabular text-3xl font-bold text-amber-900">{duration(Date.parse(s.startsAt) - now)}</p>
            </div>
          )}

          {error && <Alert>{error}</Alert>}

          {/* No reservation yet */}
          {!token && phase === 'LIVE' && (
            <Button className="w-full py-4 text-base" disabled={left === 0} loading={reserve.isPending} onClick={() => reserve.mutate()}>
              {left === 0 ? 'Sold out' : 'Buy now'}
            </Button>
          )}

          {/* Following a reservation */}
          {token && o && (
            <div className="rounded-xl p-5 ring-1 ring-slate-200">
              {o.status === 'PROCESSING' && (
                <div className="flex items-center gap-3 text-slate-700">
                  <Spinner /> Unit #{o.unitNo} is yours. Confirming your reservation…
                </div>
              )}
              {o.status === 'HELD' && !holdExpired && (
                <div className="space-y-4">
                  <p className="font-medium">
                    Unit #{o.unitNo} is held for you. Pay within{' '}
                    <span className="tabular font-bold text-rose-600">{duration(holdLeft ?? 0)}</span>
                  </p>
                  <p className="text-sm text-slate-500">
                    Wallet balance: {wallet.data ? money(wallet.data.balance) : '…'}
                    {wallet.data && wallet.data.balance < o.price && (
                      <> · <Link to="/add-money" className="font-medium text-brand-600">add money</Link> first</>
                    )}
                  </p>
                  <Button className="w-full py-3" loading={pay.isPending} onClick={() => pay.mutate()}>
                    Pay {money(o.price)} from wallet
                  </Button>
                </div>
              )}
              {o.status === 'PAID' && (
                <div className="text-center">
                  <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-2xl text-emerald-600">✓</div>
                  <p className="mt-3 text-lg font-semibold">You got unit #{o.unitNo}!</p>
                  <p className="text-sm text-slate-500">Paid {money(o.price)} from your wallet.</p>
                </div>
              )}
              {holdExpired && (
                <div className="space-y-3">
                  <Alert kind="info">Your hold on unit #{o.unitNo} expired before payment, so it went back to the pool.</Alert>
                  {phase === 'LIVE' && left > 0 && (
                    <Button className="w-full" loading={reserve.isPending} onClick={() => { setToken(null); reserve.mutate() }}>
                      Try again
                    </Button>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </Card>
    </div>
  )
}
