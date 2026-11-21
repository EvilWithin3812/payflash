import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { Sale } from '../api/types'
import { Alert, Badge, Button, Card, Field, PageTitle } from '../components/ui'

/** datetime-local value for "now + minutes", in the browser's local time. */
function localInput(minutesFromNow: number) {
  const d = new Date(Date.now() + minutesFromNow * 60_000)
  d.setSeconds(0, 0)
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

interface Reconciliation {
  healthy: boolean
  mismatchCount: number
}

export function Admin() {
  const queryClient = useQueryClient()
  const [form, setForm] = useState({
    name: 'Limited sneaker drop',
    description: 'Only a handful of pairs. One per customer.',
    price: '1999',
    totalUnits: '100',
    startsAt: localInput(1),
    endsAt: localInput(61),
    holdSeconds: '120',
  })
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value })

  const create = useMutation({
    mutationFn: async () =>
      (
        await api.post<Sale>('/admin/sales', {
          name: form.name,
          description: form.description || null,
          price: Number(form.price),
          totalUnits: Number(form.totalUnits),
          startsAt: new Date(form.startsAt).toISOString(),
          endsAt: new Date(form.endsAt).toISOString(),
          holdSeconds: Number(form.holdSeconds),
        })
      ).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sales'] }),
  })

  const recon = useQuery({
    queryKey: ['reconciliation'],
    queryFn: async () => (await api.get<Reconciliation>('/admin/reconciliation')).data,
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="lg:col-span-2">
        <PageTitle title="Create a flash sale" subtitle="Units are loaded into Redis the moment the sale is created." />
        <Card>
          <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2"><Field label="Name" required maxLength={120} value={form.name} onChange={set('name')} /></div>
            <div className="sm:col-span-2"><Field label="Description" maxLength={1000} value={form.description} onChange={set('description')} /></div>
            <Field label="Price (₹)" type="number" min="1" step="0.01" required value={form.price} onChange={set('price')} />
            <Field label="Units" type="number" min="1" max="100000" required value={form.totalUnits} onChange={set('totalUnits')} />
            <Field label="Starts" type="datetime-local" required value={form.startsAt} onChange={set('startsAt')} />
            <Field label="Ends" type="datetime-local" required value={form.endsAt} onChange={set('endsAt')} />
            <Field label="Hold time (seconds)" type="number" min="30" max="3600" required hint="How long a buyer has to pay"
              value={form.holdSeconds} onChange={set('holdSeconds')} />
            <div className="sm:col-span-2 space-y-3">
              {create.isError && <Alert>{errorMessage(create.error)}</Alert>}
              {create.isSuccess && (
                <Alert kind="success">
                  Created “{create.data.name}”. <Link className="font-semibold underline" to={`/sales/${create.data.id}`}>Open it</Link>
                </Alert>
              )}
              <Button type="submit" loading={create.isPending}>Create sale</Button>
            </div>
          </form>
        </Card>
      </div>
      <div>
        <PageTitle title="Ledger health" />
        <Card>
          <p className="text-sm text-slate-500">Every wallet balance is compared with SUM(credits) − SUM(debits) in the ledger.</p>
          <div className="mt-4 flex items-center justify-between">
            {recon.data ? (
              recon.data.healthy ? <Badge tone="green">All wallets reconcile</Badge> : <Badge tone="red">{recon.data.mismatchCount} mismatches</Badge>
            ) : recon.isError ? (
              <Badge tone="red">Check failed</Badge>
            ) : (
              <Badge>Checking…</Badge>
            )}
            <Button variant="ghost" onClick={() => recon.refetch()} loading={recon.isFetching}>Re-run</Button>
          </div>
        </Card>
      </div>
    </div>
  )
}
